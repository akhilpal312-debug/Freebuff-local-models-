package dev.akhil.freebuff.research

import dev.akhil.freebuff.core.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val provider: String,
)

/**
 * A search backend. Providers are adapters so one can be swapped for another
 * without touching the research engine.
 */
interface SearchProvider {
    val id: String
    val label: String
    val requiresApiKey: Boolean
    val note: String

    suspend fun search(query: String, limit: Int, apiKey: String?): Result<List<SearchHit>>
}

class ProviderUnavailable(message: String) : Exception(message)

/** Keyless encyclopaedia search. Always available, real JSON API, no scraping. */
class WikipediaProvider(private val client: OkHttpClient) : SearchProvider {
    override val id = "wikipedia"
    override val label = "Wikipedia"
    override val requiresApiKey = false
    override val note = "Encyclopaedia only. Good for established facts, not for news."

    override suspend fun search(query: String, limit: Int, apiKey: String?): Result<List<SearchHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded" +
                    "&format=json&srlimit=${limit.coerceIn(1, 10)}&utf8=1"
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ProviderUnavailable("Wikipedia HTTP ${response.code}")
                    val root = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val items = root["query"]?.jsonObject?.get("search")?.jsonArray ?: return@use emptyList<SearchHit>()
                    items.mapNotNull { element ->
                        val obj = element.jsonObject
                        val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val snippet = obj["snippet"]?.jsonPrimitive?.content
                            ?.replace(Regex("<[^>]+>"), "")
                            ?.trim()
                            ?: ""
                        SearchHit(
                            title = title,
                            url = "https://en.wikipedia.org/wiki/" + title.replace(' ', '_'),
                            snippet = snippet,
                            provider = id,
                        )
                    }.take(limit)
                }
            }
        }

    private companion object {
        const val USER_AGENT = "FreebuffMobile/1.0 (Android; local research module)"
    }
}

/**
 * DuckDuckGo HTML endpoint. Keyless, no tracking script. It can rate-limit or
 * change markup, so failures are surfaced as "provider unavailable" and the
 * engine moves on rather than inventing results.
 */
class DuckDuckGoProvider(private val client: OkHttpClient) : SearchProvider {
    override val id = "duckduckgo"
    override val label = "DuckDuckGo"
    override val requiresApiKey = false
    override val note = "Keyless web search. May rate-limit; results are never fabricated."

    override suspend fun search(query: String, limit: Int, apiKey: String?): Result<List<SearchHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = okhttp3.FormBody.Builder().add("q", query).build()
                val request = Request.Builder()
                    .url("https://html.duckduckgo.com/html/")
                    .header("User-Agent", "Mozilla/5.0 (Android 13; Mobile) FreebuffMobile/1.0")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ProviderUnavailable("DuckDuckGo HTTP ${response.code}")
                    val html = response.body?.string().orEmpty()
                    if (html.isBlank()) throw ProviderUnavailable("Empty response from DuckDuckGo.")
                    parse(html, limit)
                }
            }
        }

    private fun parse(html: String, limit: Int): List<SearchHit> {
        val results = mutableListOf<SearchHit>()
        val blockRegex = Regex(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>([\\s\\S]{0,1200}?)class=\"result__snippet\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        blockRegex.findAll(html).forEach { match ->
            if (results.size >= limit) return@forEach
            val rawUrl = match.groupValues[1]
            val title = stripTags(match.groupValues[2])
            val snippet = stripTags(match.groupValues[4])
            val url = decodeDuckDuckGoUrl(rawUrl) ?: return@forEach
            if (title.isBlank()) return@forEach
            results += SearchHit(title, url, snippet, "duckduckgo")
        }
        if (results.isEmpty()) {
            // Markup changed or the request was blocked: report, do not invent.
            val fallback = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).mapNotNull { match ->
                    val url = decodeDuckDuckGoUrl(match.groupValues[1]) ?: return@mapNotNull null
                    SearchHit(stripTags(match.groupValues[2]), url, "", "duckduckgo")
                }.take(limit).toList()
            if (fallback.isEmpty()) {
                throw ProviderUnavailable("DuckDuckGo returned a page with no parseable results (rate limit?).")
            }
            return fallback
        }
        return results
    }

    private fun decodeDuckDuckGoUrl(raw: String): String? {
        val decoded = raw.replace("&amp;", "&")
        val redirect = Regex("uddg=([^&]+)").find(decoded)?.groupValues?.get(1)
            ?: return decoded.takeIf { it.startsWith("http") }
        return runCatching { java.net.URLDecoder.decode(redirect, "UTF-8") }.getOrNull()
            ?.takeIf { it.startsWith("http") }
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Bring-your-own-key provider for users who want broader coverage. */
class TavilyProvider(private val client: OkHttpClient) : SearchProvider {
    override val id = "tavily"
    override val label = "Tavily"
    override val requiresApiKey = true
    override val note = "Requires an API key stored in the Android Keystore. Results are metered by Tavily."

    override suspend fun search(query: String, limit: Int, apiKey: String?): Result<List<SearchHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (apiKey.isNullOrBlank()) throw ProviderUnavailable("Tavily needs an API key.")
                val payload = buildString {
                    append("{\"api_key\":\"").append(apiKey).append("\",")
                    append("\"query\":\"").append(query.replace("\"", "\\\"")).append("\",")
                    append("\"max_results\":").append(limit.coerceIn(1, 10)).append("}")
                }
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ProviderUnavailable("Tavily HTTP ${response.code}")
                    val root = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    root["results"]?.jsonArray?.mapNotNull { element ->
                        val obj = element.jsonObject
                        val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        SearchHit(
                            title = obj["title"]?.jsonPrimitive?.content ?: url,
                            url = url,
                            snippet = obj["content"]?.jsonPrimitive?.content?.take(400) ?: "",
                            provider = id,
                        )
                    } ?: emptyList()
                }
            }
        }
}

class SearchRegistry(
    private val wikipedia: WikipediaProvider,
    private val duckDuckGo: DuckDuckGoProvider,
    private val tavily: TavilyProvider,
) {
    fun all(): List<SearchProvider> = listOf(duckDuckGo, wikipedia, tavily)

    fun byId(id: String): SearchProvider? = all().firstOrNull { it.id == id }

    fun defaultOrder(preferredId: String, hasApiKey: Boolean): List<SearchProvider> {
        val preferred = byId(preferredId)
        val rest = all().filter { it.id != preferredId }
            .filter { hasApiKey || !it.requiresApiKey }
        return listOfNotNull(preferred?.takeIf { hasApiKey || !it.requiresApiKey }) + rest
    }

    companion object {
        fun create(client: OkHttpClient = defaultClient): SearchRegistry =
            SearchRegistry(WikipediaProvider(client), DuckDuckGoProvider(client), TavilyProvider(client))

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}

/** Fetches and flattens a web page so the model can cite real text. */
class PageFetcher(private val client: OkHttpClient = SearchRegistry.defaultClient) {

    data class Page(
        val url: String,
        val title: String,
        val text: String,
        val reachable: Boolean,
        val note: String = "",
    )

    suspend fun fetch(url: String, maxChars: Int = 6000): Result<Page> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) FreebuffMobile/1.0")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful) throw ProviderUnavailable("HTTP ${response.code} for $url")
                if (!contentType.contains("text/html") && !contentType.contains("text/plain")) {
                    throw ProviderUnavailable("Unsupported content type: $contentType")
                }
                val html = response.body?.string().orEmpty()
                val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.get(1)?.let(::clean) ?: url
                val withoutScripts = html
                    .replace(Regex("(?is)<script.*?</script>"), " ")
                    .replace(Regex("(?is)<style.*?</style>"), " ")
                    .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
                    .replace(Regex("(?is)<nav.*?</nav>"), " ")
                    .replace(Regex("(?is)<footer.*?</footer>"), " ")
                val text = clean(withoutScripts).take(maxChars)
                Page(url, title, text, reachable = true)
            }
        }.recoverCatching { error ->
            Page(url, url, "", reachable = false, note = error.message ?: "Could not load page")
        }
    }

    /** Confirms a citation's link still resolves before it is published in a report. */
    suspend fun verify(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun clean(fragment: String): String =
        fragment.replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
}
