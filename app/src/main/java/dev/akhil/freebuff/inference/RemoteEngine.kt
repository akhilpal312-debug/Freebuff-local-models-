package dev.akhil.freebuff.inference

import dev.akhil.freebuff.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteConfig(
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
)

/**
 * Optional OpenAI-compatible remote engine.
 *
 * Rules enforced here and in the UI:
 *  - never selected implicitly;
 *  - every response is labelled as remote;
 *  - only the conversation text the user chose to send leaves the device —
 *    project files, recordings and models never do;
 *  - credentials come from the Android Keystore, never from prefs or logs.
 */
class RemoteEngine(private val secure: SecureStore) : InferenceEngine {

    override val id: String = "remote"
    override val displayName: String = "Remote provider"
    override val isRemote: Boolean = true

    @Volatile private var modelPath: String? = null
    @Volatile private var modelName: String? = null
    @Volatile private var config: RemoteConfig? = null
    private val stopRequested = AtomicBoolean(false)

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override val loadedModelPath: String? get() = modelPath
    override val loadedModelName: String? get() = modelName

    fun configuration(): RemoteConfig? = config

    fun configure(config: RemoteConfig?) {
        this.config = config
        this.modelName = config?.model
        this.modelPath = config?.baseUrl
    }

    override fun isLoaded(): Boolean = config != null

    override suspend fun load(
        modelPath: String,
        modelName: String,
        contextTokens: Int,
        threads: Int,
    ): EngineLoad = withContext(Dispatchers.IO) {
        val config = configuration()
            ?: return@withContext EngineLoad.Failure(
                EngineFailure.LOAD_ERROR,
                "No remote provider configured. Add the base URL, model name and key in Settings.",
            )
        // A cheap round trip proves the endpoint and key before a long request.
        runCatching {
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/models")
                .apply { if (!config.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${config.apiKey}") }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Provider returned HTTP ${response.code} for /models.")
                }
            }
        }.onFailure {
            return@withContext EngineLoad.Failure(
                EngineFailure.LOAD_ERROR,
                "Could not reach the remote provider: ${it.message}",
            )
        }
        EngineLoad.Success("Remote · ${config.model}", 8192)
    }

    override suspend fun unload() {
        config = null
        modelName = null
        modelPath = null
    }

    override fun stream(request: GenRequest): Flow<GenEvent> = callbackFlow {
        val config = configuration()
        if (config == null) {
            trySend(GenEvent.Failure("No remote provider configured."))
            close()
            return@callbackFlow
        }
        stopRequested.set(false)
        val startedAt = System.currentTimeMillis()
        var count = 0

        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("temperature", request.params.temperature.toDouble())
            put("max_tokens", request.params.maxTokens)
            put("messages", buildJsonArray {
                request.turns.forEach { turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        put("content", turn.content)
                    })
                }
            })
        }

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (!config.apiKey.isNullOrBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    trySend(GenEvent.Failure("Provider error HTTP ${response.code}: ${response.message}"))
                    close()
                    return@callbackFlow
                }
                val source = response.body?.source() ?: run {
                    trySend(GenEvent.Failure("Empty response from provider."))
                    close()
                    return@callbackFlow
                }
                while (!source.exhausted()) {
                    if (stopRequested.get()) break
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    runCatching {
                        val root = json.parseToJsonElement(payload).jsonObject
                        val delta = root["choices"]?.let { it as? kotlinx.serialization.json.JsonArray }
                            ?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
                        val text = delta?.get("content")?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) {
                            count++
                            trySend(GenEvent.Token(text))
                        }
                    }
                }
            }
            trySend(
                GenEvent.Done(
                    tokenCount = count,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    stopped = stopRequested.get(),
                ),
            )
        } catch (error: Exception) {
            trySend(GenEvent.Failure(error.message ?: "Remote generation failed."))
        } finally {
            close()
        }
        awaitClose { stopRequested.set(true) }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        stopRequested.set(true)
    }

    override fun backendInfo(): String =
        configuration()?.let { "Remote: ${it.name} (${it.model}) — this reply left your device" }
            ?: "Remote: not configured"
}
