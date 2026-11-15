package dev.akhil.freebuff.research

import dev.akhil.freebuff.core.Ids
import dev.akhil.freebuff.core.NetworkMonitor
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ResearchSessionEntity
import dev.akhil.freebuff.data.db.ResearchSourceEntity
import dev.akhil.freebuff.inference.ChatTurn
import dev.akhil.freebuff.inference.EngineManager
import dev.akhil.freebuff.inference.GenEvent
import dev.akhil.freebuff.inference.GenParams
import dev.akhil.freebuff.inference.GenRequest
import dev.akhil.freebuff.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class ResearchProgress(
    val running: Boolean = false,
    val step: String = "",
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val sourcesFound: Int = 0,
    val pagesRead: Int = 0,
    val error: String? = null,
    val report: String = "",
    val sessionId: String? = null,
)

/**
 * Multi-step research that only ever reports what it actually fetched.
 *
 * Pipeline: plan queries → search providers → de-duplicate → fetch pages →
 * extract passages → synthesise → verify citations → persist.
 *
 * If no local model is loaded the engine still produces a real extractive report
 * from the fetched pages, clearly labelled as such. It never invents a source.
 */
class ResearchEngine(
    private val context: android.content.Context,
    private val db: AppDatabase,
    private val search: SearchRegistry,
    private val fetcher: PageFetcher,
    private val engines: EngineManager,
    private val secure: SecureStore,
) {
    private val _progress = MutableStateFlow(ResearchProgress())
    val progress: StateFlow<ResearchProgress> = _progress.asStateFlow()

    fun observeSessions() = db.research().observeSessions()

    fun observeSources(sessionId: String) = db.research().observeSources(sessionId)

    suspend fun getSession(id: String): ResearchSessionEntity? = db.research().getSession(id)

    suspend fun sources(sessionId: String) = db.research().sources(sessionId)

    suspend fun run(
        question: String,
        maxSources: Int = 8,
        preferredProvider: String = "duckduckgo",
        modelContextTokens: Int = 2048,
    ): ResearchProgress {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            return ResearchProgress(error = "Enter a research question first.")
        }
        if (!NetworkMonitor.isOnline(context)) {
            val offline = ResearchProgress(
                error = "Deep Research needs an internet connection. Your local model still works offline in Chat.",
            )
            _progress.value = offline
            return offline
        }

        val sessionId = Ids.new()
        val fetchStamp = System.currentTimeMillis()
        db.research().upsertSession(
            ResearchSessionEntity(
                id = sessionId,
                question = trimmed,
                plan = "",
                report = "",
                status = "RUNNING",
                createdAt = fetchStamp,
            ),
        )

        try {
            // 1. Plan ------------------------------------------------------
            _progress.value = ResearchProgress(true, "Planning the research", 1, 5, sessionId = sessionId)
            val queries = planQueries(trimmed)
            db.research().upsertSession(
                ResearchSessionEntity(
                    id = sessionId,
                    question = trimmed,
                    plan = queries.joinToString("\n") { "- $it" },
                    report = "",
                    status = "RUNNING",
                    createdAt = fetchStamp,
                ),
            )

            // 2. Search ---------------------------------------------------
            _progress.value = ResearchProgress(true, "Searching ${queries.size} queries", 2, 5, sessionId = sessionId)
            val apiKey = secure.get(SecureStore.KEY_SEARCH_API)
            val providers = search.defaultOrder(preferredProvider, !apiKey.isNullOrBlank())
            val hits = mutableListOf<SearchHit>()
            val seenUrls = mutableSetOf<String>()
            var providerFailure: String? = null

            queries.forEach { query ->
                for (provider in providers) {
                    if (hits.size >= maxSources) return@forEach
                    val key = if (provider.requiresApiKey) apiKey else null
                    provider.search(query, 4, key).onSuccess { results ->
                        results.forEach { hit ->
                            val normalized = hit.url.substringBefore('#')
                            if (seenUrls.add(normalized) && hits.size < maxSources) hits += hit
                        }
                    }.onFailure { error ->
                        providerFailure = "${provider.label}: ${error.message}"
                    }
                }
            }

            if (hits.isEmpty()) {
                val message = buildString {
                    append("No sources could be retrieved. ")
                    if (providerFailure != null) append("Last provider error — $providerFailure. ")
                    append("Nothing was generated, because a research report without sources would be fabricated.")
                }
                db.research().upsertSession(
                    ResearchSessionEntity(
                        id = sessionId,
                        question = trimmed,
                        plan = queries.joinToString("\n"),
                        report = message,
                        status = "FAILED",
                        createdAt = fetchStamp,
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
                val state = ResearchProgress(error = message, sessionId = sessionId)
                _progress.value = state
                return state
            }

            // 3. Read pages ----------------------------------------------
            _progress.value = ResearchProgress(
                true, "Reading source pages", 3, 5, sourcesFound = hits.size, sessionId = sessionId,
            )
            val pages = mutableListOf<PageFetcher.Page>()
            hits.forEachIndexed { index, hit ->
                _progress.value = ResearchProgress(
                    true, "Reading page ${index + 1} of ${hits.size}", 3, 5,
                    sourcesFound = hits.size, pagesRead = pages.size, sessionId = sessionId,
                )
                val page = fetcher.fetch(hit.url, maxChars = 5000).getOrNull()
                    ?: PageFetcher.Page(hit.url, hit.title, "", reachable = false, note = "unreachable")
                if (page.reachable && page.text.isNotBlank()) {
                    pages += page
                    db.research().insertSource(
                        ResearchSourceEntity(
                            id = Ids.new(),
                            sessionId = sessionId,
                            title = page.title.ifBlank { hit.title },
                            url = hit.url,
                            snippet = page.text.take(600),
                            provider = hit.provider,
                            fetchedAt = System.currentTimeMillis(),
                            verified = true,
                        ),
                    )
                } else {
                    db.research().insertSource(
                        ResearchSourceEntity(
                            id = Ids.new(),
                            sessionId = sessionId,
                            title = hit.title,
                            url = hit.url,
                            snippet = hit.snippet,
                            provider = hit.provider,
                            fetchedAt = System.currentTimeMillis(),
                            verified = false,
                            note = page.note.ifBlank { "Could not read this page; only the search snippet is available." },
                        ),
                    )
                }
            }

            if (pages.isEmpty()) {
                val message = "Every source page failed to load, so no report was produced. " +
                    "Only the search snippets were available and they are not enough to support a report."
                db.research().upsertSession(
                    ResearchSessionEntity(
                        id = sessionId,
                        question = trimmed,
                        plan = queries.joinToString("\n"),
                        report = message,
                        status = "FAILED",
                        createdAt = fetchStamp,
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
                val state = ResearchProgress(error = message, sessionId = sessionId, sourcesFound = hits.size)
                _progress.value = state
                return state
            }

            // 4. Synthesise ----------------------------------------------
            _progress.value = ResearchProgress(
                true, "Writing the report", 4, 5,
                sourcesFound = hits.size, pagesRead = pages.size, sessionId = sessionId,
            )
            val report = synthesise(trimmed, pages, modelContextTokens)

            // 5. Verify links --------------------------------------------
            _progress.value = ResearchProgress(
                true, "Checking citations", 5, 5,
                sourcesFound = hits.size, pagesRead = pages.size, sessionId = sessionId,
            )
            val persisted = db.research().sources(sessionId)
            persisted.take(6).forEach { source ->
                val alive = fetcher.verify(source.url)
                db.research().updateSource(
                    source.id,
                    alive,
                    if (alive) source.note else "Link did not respond when checked; treat with caution.",
                )
            }

            db.research().upsertSession(
                ResearchSessionEntity(
                    id = sessionId,
                    question = trimmed,
                    plan = queries.joinToString("\n") { "- $it" },
                    report = report,
                    status = "COMPLETED",
                    createdAt = fetchStamp,
                    finishedAt = System.currentTimeMillis(),
                ),
            )
            val done = ResearchProgress(
                running = false,
                step = "Report ready",
                stepIndex = 5,
                stepCount = 5,
                sourcesFound = hits.size,
                pagesRead = pages.size,
                report = report,
                sessionId = sessionId,
            )
            _progress.value = done
            return done
        } catch (cancelled: CancellationException) {
            db.research().upsertSession(
                ResearchSessionEntity(
                    id = sessionId,
                    question = trimmed,
                    plan = "",
                    report = "Cancelled.",
                    status = "CANCELLED",
                    createdAt = fetchStamp,
                    finishedAt = System.currentTimeMillis(),
                ),
            )
            throw cancelled
        } catch (error: Exception) {
            val state = ResearchProgress(
                error = error.message ?: error.javaClass.simpleName,
                sessionId = sessionId,
            )
            _progress.value = state
            db.research().upsertSession(
                ResearchSessionEntity(
                    id = sessionId,
                    question = trimmed,
                    plan = "",
                    report = "Research failed: ${state.error}",
                    status = "FAILED",
                    createdAt = fetchStamp,
                    finishedAt = System.currentTimeMillis(),
                ),
            )
            return state
        }
    }

    /** Query planning: model-assisted when a model is loaded, deterministic otherwise. */
    private suspend fun planQueries(question: String): List<String> {
        val deterministic = buildList {
            add(question)
            val words = question.split(Regex("\\s+")).filter { it.length > 4 }
            if (words.isNotEmpty()) add(words.take(4).joinToString(" ") + " explained")
            add(question + " latest")
        }.distinct().take(3)

        val active = engines.active.value ?: return deterministic
        if (active.remote) return deterministic
        val engine = engines.engineFor(false)
        val prompt = "Write up to 3 short web search queries for this research question, one per line, " +
            "no numbering, no commentary.\nQuestion: $question"
        val builder = StringBuilder()
        val completed = withTimeoutOrNull(45_000) {
            engine.stream(
                GenRequest(
                    turns = listOf(ChatTurn(ChatTurn.ROLE_USER, prompt)),
                    params = GenParams(maxTokens = 90, temperature = 0.3f, contextTokens = active.contextTokens),
                ),
            ).collect { event -> if (event is GenEvent.Token) builder.append(event.text) }
            true
        } ?: false
        if (!completed) return deterministic

        val parsed = builder.toString().lines()
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ')').trim() }
            .filter { it.length in 6..120 && !it.contains("query", true) }
            .take(3)
        return (parsed + deterministic).distinct().take(3)
    }

    private suspend fun synthesise(
        question: String,
        pages: List<PageFetcher.Page>,
        contextTokens: Int,
    ): String {
        val numbered = pages.mapIndexed { index, page ->
            "[${index + 1}] ${page.title} — ${page.url}\n${page.text.take(1800)}"
        }.joinToString("\n\n")

        val active = engines.active.value
        if (active != null) {
            val engine = if (active.remote) engines.engineFor(true) else engines.engineFor(false)
            val prompt = buildString {
                appendLine(dev.akhil.freebuff.ai.SystemPrompts.forMode(dev.akhil.freebuff.ai.AiMode.DEEP_RESEARCH))
                appendLine()
                appendLine("Research question: $question")
                appendLine()
                appendLine("Sources:")
                appendLine(numbered.take(contextTokens * 3))
                appendLine()
                appendLine("Write the report now. Use [n] citations that match the sources above.")
            }
            val builder = StringBuilder()
            val finished = withTimeoutOrNull(300_000) {
                engine.stream(
                    GenRequest(
                        turns = listOf(ChatTurn(ChatTurn.ROLE_USER, prompt)),
                        params = GenParams(maxTokens = 700, temperature = 0.4f, contextTokens = contextTokens),
                    ),
                ).collect { event -> if (event is GenEvent.Token) builder.append(event.text) }
                true
            } ?: false
            val text = builder.toString().trim()
            if (finished && text.length > 120) {
                return text + "\n\n---\n\nSources\n" + bibliography(pages)
            }
        }

        // No model: produce a genuine extractive report from the fetched text.
        return buildString {
            appendLine("# $question")
            appendLine()
            appendLine(
                "_No local model was loaded, so this is an extractive summary: the passages below are " +
                    "quoted from the fetched pages, not generated._",
            )
            appendLine()
            pages.forEachIndexed { index, page ->
                appendLine("## [${index + 1}] ${page.title}")
                appendLine(page.url)
                appendLine()
                val keyTerms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.length > 4 }
                val sentences = page.text.split(Regex("(?<=[.!?])\\s+"))
                val relevant = sentences
                    .sortedByDescending { sentence ->
                        keyTerms.count { sentence.contains(it, ignoreCase = true) }
                    }
                    .take(3)
                relevant.forEach { appendLine("> ${it.take(400)}") }
                appendLine()
            }
            appendLine("---")
            appendLine("Sources")
            append(bibliography(pages))
        }
    }

    private fun bibliography(pages: List<PageFetcher.Page>): String =
        pages.mapIndexed { index, page ->
            "[${index + 1}] ${page.title} — ${page.url}"
        }.joinToString("\n")
}
