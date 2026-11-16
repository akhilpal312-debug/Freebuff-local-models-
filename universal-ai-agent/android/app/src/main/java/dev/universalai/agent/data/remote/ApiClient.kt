@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.universalai.agent.data.remote

import dev.universalai.agent.data.local.SessionStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The single HTTP entry point for the API. Adds the bearer token, maps error
 * envelopes to [ApiException], retries once after a token refresh, and exposes a
 * streaming SSE flow for chat and run events.
 */
class ApiClient(
    private val store: SessionStore,
) {

    companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            isLenient = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
        private val mediaType = JSON_MEDIA_TYPE.toMediaType()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun url(path: String): String = store.apiBaseUrl.trimEnd('/') + path

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder().url(url(path)).header("Accept", "application/json")

    suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T =
        execute(requestBuilder(path).get().build(), deserializer)

    suspend fun <T> post(
        path: String,
        body: String = "{}",
        deserializer: DeserializationStrategy<T>,
    ): T = execute(
        requestBuilder(path).post(body.toRequestBody(mediaType)).build(),
        deserializer,
    )

    suspend fun <T> patch(
        path: String,
        body: String,
        deserializer: DeserializationStrategy<T>,
    ): T = execute(
        requestBuilder(path).patch(body.toRequestBody(mediaType)).build(),
        deserializer,
    )

    suspend fun <T> put(
        path: String,
        body: String,
        deserializer: DeserializationStrategy<T>,
    ): T = execute(
        requestBuilder(path).put(body.toRequestBody(mediaType)).build(),
        deserializer,
    )

    suspend fun delete(path: String) {
        executeUnit(requestBuilder(path).delete().build())
    }

    suspend fun <T> upload(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        bytes: ByteArray,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .apply {
                fields.forEach { (key, value) -> addFormDataPart(key, value) }
                addFormDataPart(
                    fileField,
                    fileName,
                    bytes.toRequestBody("application/zip".toMediaType()),
                )
            }
            .build()
        return execute(requestBuilder(path).post(multipart).build(), deserializer)
    }

    suspend fun postUnit(path: String, body: String = "{}") {
        executeUnit(requestBuilder(path).post(body.toRequestBody(mediaType)).build())
    }

    private suspend fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): T {
        val response = perform(request, allowRefresh = true)
        response.use {
            val text = it.body?.string().orEmpty()
            val status = it.code
            if (!it.isSuccessful) throw parseError(status, text)
            return runCatching { json.decodeFromString(deserializer, text) }
                .getOrElse { throw ApiException(status, "decode_error", "Unexpected server response.") }
        }
    }

    private suspend fun executeUnit(request: Request) {
        val response = perform(request, allowRefresh = true)
        response.use {
            if (!it.isSuccessful) throw parseError(it.code, it.body?.string().orEmpty())
        }
    }

    private suspend fun perform(request: Request, allowRefresh: Boolean): okhttp3.Response =
        withContext(Dispatchers.IO) {
            if (store.apiBaseUrl.isBlank()) throw ApiException.notConfigured()
            val response = try {
                http.newCall(request).execute()
            } catch (io: IOException) {
                throw ApiException.network(io)
            }
            response
        }

    /**
     * Stream a server-sent event response. The flow is cancelled when the collector
     * leaves the composition, which closes the HTTP call and stops generation
     * server-side.
     */
    fun sse(path: String, body: String): Flow<SseEvent> = flow {
        val request = requestBuilder(path)
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(mediaType))
            .build()
        val response = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute()
            } catch (io: IOException) {
                throw ApiException.network(io)
            }
        }
        response.use { res ->
            if (!res.isSuccessful) {
                throw parseError(res.code, res.body?.string().orEmpty())
            }
            val source = res.body?.source() ?: throw ApiException(
                500, "empty_stream", "The server returned an empty stream.",
            )
            val parser = SseParser()
            while (!source.exhausted() && currentCoroutineContext().isActive) {
                val line = source.readUtf8Line() ?: break
                parser.feed(line)?.let { emit(it) }
            }
            parser.flush()?.let { emit(it) }
        }
    }

    fun decodeEvent(event: SseEvent): JsonObject? = runCatching {
        json.parseToJsonElement(event.data).jsonObject
    }.getOrNull()

    private fun parseError(status: Int, body: String): ApiException {
        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val code = parsed?.get("code")?.toString()?.trim('"') ?: "http_$status"
        val message = parsed?.get("message")?.toString()?.trim('"')
            ?: when {
                status == 401 -> "The server rejected the request (401)."
                status == 403 -> "You don't have access to that."
                status == 404 -> "That item no longer exists."
                status == 429 -> "Too many requests. Please slow down."
                status >= 500 -> "The server had a problem. Try again."
                else -> "Request failed ($status)."
            }
        return ApiException(status, code, message)
    }
}
