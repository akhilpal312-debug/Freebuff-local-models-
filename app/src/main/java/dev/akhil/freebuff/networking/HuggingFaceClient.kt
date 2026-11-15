package dev.akhil.freebuff.networking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Metadata resolved from the Hugging Face hub before a download starts. */
data class RemoteFileMeta(
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
)

class NetworkUnavailableException(message: String) : Exception(message)

/**
 * Thin Hugging Face client used only for metadata resolution. The actual
 * transfer is handled by [dev.akhil.freebuff.models.DownloadWorker] so that it
 * can be resumable, verified and survive process death.
 *
 * No CLI tool, no script from a model repository, no arbitrary code execution.
 */
class HuggingFaceClient(
    private val client: OkHttpClient = defaultClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun resolveUrl(repo: String, fileName: String): String =
        "https://huggingface.co/$repo/resolve/main/${fileName.trimStart('/')}"

    /** Reads Content-Length plus the LFS sha256 exposed by the CDN. */
    suspend fun resolve(repo: String, fileName: String): Result<RemoteFileMeta> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl(repo, fileName)
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Hub returned HTTP ${response.code} for $repo/$fileName")
                }
                val finalUrl = response.request.url.toString()
                val size = response.header("Content-Length")?.toLongOrNull()
                    ?: response.header("x-linked-size")?.toLongOrNull()
                    ?: 0L
                val etag = response.header("x-linked-etag")
                    ?.trim('"')
                    ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                RemoteFileMeta(finalUrl, size, etag)
            }
        }
    }

    /** Fallback metadata lookup through the model API (works when the CDN hides headers). */
    suspend fun resolveViaApi(repo: String, fileName: String): Result<RemoteFileMeta> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://huggingface.co/api/models/$repo?blobs=true")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("API HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(body).jsonObject
                    val sibling = root["siblings"]?.jsonArray
                        ?.map { it.jsonObject }
                        ?.firstOrNull { it["rfilename"]?.jsonPrimitive?.content == fileName }
                        ?: throw java.io.IOException("File $fileName not found in $repo")
                    val size = sibling["size"]?.jsonPrimitive?.longOrNull ?: 0L
                    val sha = sibling["lfs"]?.jsonObject?.get("oid")?.jsonPrimitive?.content
                    RemoteFileMeta(resolveUrl(repo, fileName), size, sha)
                }
            }
        }

    /** Validates that a repository exists and is reachable, and returns its license if declared. */
    suspend fun repositoryInfo(repo: String): Result<RepoInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://huggingface.co/api/models/$repo")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw java.io.IOException("Repository $repo is gated. Accept its license on huggingface.co first.")
                }
                if (response.code == 404) throw java.io.IOException("Repository $repo does not exist.")
                if (!response.isSuccessful) throw java.io.IOException("Hub HTTP ${response.code}")
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val gated = root["gated"]?.jsonPrimitive?.content?.let { it == "true" || it == "auto" } ?: false
                val license = root["cardData"]?.jsonObject?.get("license")?.jsonPrimitive?.content
                val files = root["siblings"]?.jsonArray?.mapNotNull {
                    it.jsonObject["rfilename"]?.jsonPrimitive?.content
                } ?: emptyList()
                RepoInfo(repo, gated, license, files)
            }
        }
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

data class RepoInfo(
    val repo: String,
    val gated: Boolean,
    val license: String?,
    val files: List<String>,
)
