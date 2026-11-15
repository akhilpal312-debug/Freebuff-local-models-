package dev.akhil.freebuff.ai

import dev.akhil.freebuff.core.AppStorage
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.MessageEntity
import dev.akhil.freebuff.data.db.MessageRole
import dev.akhil.freebuff.data.db.MessageStatus
import dev.akhil.freebuff.data.prefs.SettingsStore
import dev.akhil.freebuff.inference.ActiveModel
import dev.akhil.freebuff.inference.ChatTurn
import dev.akhil.freebuff.inference.EngineManager
import dev.akhil.freebuff.inference.GenEvent
import dev.akhil.freebuff.inference.GenParams
import dev.akhil.freebuff.inference.GenRequest
import dev.akhil.freebuff.inference.PromptRenderer
import dev.akhil.freebuff.projects.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Everything the chat screen renders. */
data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "",
    val messages: List<MessageEntity> = emptyList(),
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val mode: AiMode = AiMode.FAST_CHAT,
    val modelLabel: String = "No model loaded",
    val notice: String? = null,
    val error: String? = null,
    val tokensPerSecond: String = "",
)

/**
 * Drives a chat turn end to end: prompt assembly, streaming, persistence,
 * cancellation and honest error reporting.
 *
 * The app never synthesises a reply itself. If the engine cannot run, the
 * assistant message is stored with status ERROR and the real reason is shown.
 */
class ChatOrchestrator(
    private val db: AppDatabase,
    private val repo: ChatRepository,
    private val engines: EngineManager,
    private val settings: SettingsStore,
    private val workspace: WorkspaceManager,
    private val storage: AppStorage,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generationJob: Job? = null
    private var observeJob: Job? = null

    fun open(conversationId: String) {
        observeJob?.cancel()
        _state.value = _state.value.copy(conversationId = conversationId, error = null, notice = null)
        observeJob = scope.launch {
            repo.observeConversation(conversationId).collect { conversation ->
                if (conversation != null) {
                    _state.value = _state.value.copy(
                        title = conversation.title,
                        mode = AiMode.fromName(conversation.mode),
                    )
                }
            }
        }
        scope.launch {
            repo.observeMessages(conversationId).collect { messages ->
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    fun setMode(mode: AiMode) {
        _state.value = _state.value.copy(mode = mode)
        val id = _state.value.conversationId ?: return
        scope.launch { repo.touch(id, null, mode) }
    }

    fun refreshModelLabel() {
        val active = engines.active.value
        val label = active?.displayName
            ?: engines.state.value.let { state ->
                when (state) {
                    is dev.akhil.freebuff.inference.LoadState.Loading -> "Loading ${state.modelName}…"
                    is dev.akhil.freebuff.inference.LoadState.Failed -> "No model loaded"
                    else -> "No model loaded"
                }
            }
        _state.value = _state.value.copy(modelLabel = label)
    }

    /** Sends a user message, creating a conversation if needed. */
    fun send(text: String, attachments: List<String> = emptyList(), projectId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (generationJob?.isActive == true) {
            _state.value = _state.value.copy(notice = "Still generating. Stop the current reply first.")
            return
        }
        scope.launch {
            val conversationId = _state.value.conversationId ?: run {
                val conversation = repo.createConversation(
                    modelId = engines.active.value?.recordId ?: settings.current().activeModelId,
                    mode = _state.value.mode,
                )
                open(conversation.id)
                conversation.id
            }
            repo.addUserMessage(
                conversationId = conversationId,
                text = trimmed,
                mode = _state.value.mode,
                modelId = engines.active.value?.recordId ?: "none",
                attachments = attachments,
            )
            generate(conversationId, projectId)
        }
    }

    fun regenerate(projectId: String? = null) {
        val conversationId = _state.value.conversationId ?: return
        if (generationJob?.isActive == true) return
        scope.launch {
            val messages = repo.messages(conversationId)
            val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT.name }
            if (lastAssistant != null) {
                repo.truncateAfter(conversationId, lastAssistant.createdAt - 1)
            }
            generate(conversationId, projectId)
        }
    }

    fun editUserMessage(messageId: String, newText: String, projectId: String? = null) {
        val conversationId = _state.value.conversationId ?: return
        scope.launch {
            val target = db.messages().get(messageId) ?: return@launch
            if (target.role != MessageRole.USER.name) return@launch
            db.messages().updateContent(messageId, newText.trim())
            db.messages().deleteAfter(conversationId, target.createdAt)
            generate(conversationId, projectId)
        }
    }

    private fun generate(conversationId: String, projectId: String?) {
        val active = engines.active.value
        if (active == null) {
            _state.value = _state.value.copy(
                error = noModelMessage(),
                isGenerating = false,
            )
            return
        }
        val engine = engines.engineFor(active.remote)

        generationJob = scope.launch {
            val appSettings = runCatching { settings.current() }.getOrNull()
            _state.value = _state.value.copy(isGenerating = true, streamingText = "", error = null, notice = null)
            val mode = _state.value.mode
            val assistant = repo.beginAssistantMessage(
                conversationId = conversationId,
                mode = mode,
                modelId = active.recordId,
                remote = active.remote,
            )
            val history = repo.messages(conversationId)
                .filter { it.id != assistant.id && it.status != MessageStatus.ERROR.name }
                .filter { it.content.isNotBlank() }

            val turns = mutableListOf<ChatTurn>()
            turns += ChatTurn(ChatTurn.ROLE_SYSTEM, SystemPrompts.forMode(mode))
            if (mode == AiMode.CODING_AGENT || mode == AiMode.BUILD_MODE) {
                projectId?.let { id ->
                    val context = workspace.projectContext(id)
                    if (context.isNotBlank()) {
                        turns += ChatTurn(
                            ChatTurn.ROLE_SYSTEM,
                            "Open project files (paths only):\n$context",
                        )
                    }
                }
            }
            // Keep the tail of the conversation that fits the context window.
            val budgetChars = ((appSettings?.contextTokens ?: 2048) * 3.2).toInt().coerceIn(1200, 12000)
            var used = 0
            val selected = ArrayDeque<MessageEntity>()
            for (message in history.reversed()) {
                used += message.content.length
                if (used > budgetChars) break
                selected.addFirst(message)
            }
            selected.forEach { message ->
                turns += ChatTurn(
                    if (message.role == MessageRole.USER.name) ChatTurn.ROLE_USER else ChatTurn.ROLE_ASSISTANT,
                    message.content,
                )
            }

            val params = GenParams(
                maxTokens = appSettings?.maxOutputTokens ?: 512,
                temperature = appSettings?.temperature ?: 0.7f,
                topP = 0.95f,
                topK = 40,
                contextTokens = engines.active.value?.contextTokens ?: 2048,
                threads = appSettings?.threads ?: 0,
            )

            val buffer = StringBuilder()
            var tokenCount = 0
            var elapsed = 0L
            var stopped = false
            var failure: String? = null

            try {
                engine.stream(GenRequest(turns, params)).collect { event ->
                    when (event) {
                        is GenEvent.Token -> {
                            buffer.append(event.text)
                            tokenCount++
                            val display = if (active.displayName.contains("Qwen3", true)) {
                                PromptRenderer.stripThinkBlocks(buffer.toString())
                            } else {
                                buffer.toString()
                            }
                            _state.value = _state.value.copy(streamingText = display)
                            repo.appendStreamingContent(assistant.id, display)
                        }
                        is GenEvent.Done -> {
                            elapsed = event.elapsedMs
                            stopped = event.stopped
                        }
                        is GenEvent.Failure -> {
                            failure = event.message
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                stopped = true
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            }

            val finalText = if (active.displayName.contains("Qwen3", true)) {
                PromptRenderer.stripThinkBlocks(buffer.toString())
            } else {
                buffer.toString()
            }

            val status = when {
                failure != null -> MessageStatus.ERROR
                stopped -> MessageStatus.STOPPED
                finalText.isBlank() -> MessageStatus.ERROR
                else -> MessageStatus.COMPLETE
            }
            val errorText = when {
                failure != null -> failure
                finalText.isBlank() && failure == null ->
                    "The model produced no text. Try a shorter prompt, or pick a different model."
                else -> null
            }

            repo.finishMessage(
                messageId = assistant.id,
                content = finalText.ifBlank { "" },
                status = status,
                elapsedMs = elapsed,
                tokens = tokenCount,
                error = errorText,
                conversationId = conversationId,
            )
            if (errorText != null) {
                db.messages().finish(assistant.id, finalText, status.name, elapsed, tokenCount, errorText)
            }

            _state.value = _state.value.copy(
                isGenerating = false,
                streamingText = "",
                error = errorText,
                tokensPerSecond = if (tokenCount > 0) {
                    dev.akhil.freebuff.core.Format.tokensPerSecond(tokenCount, elapsed)
                } else {
                    ""
                },
            )
            refreshModelLabel()
        }
    }

    fun stopGeneration() {
        engines.currentEngine()?.stop()
        generationJob?.cancel()
        _state.value = _state.value.copy(isGenerating = false)
    }

    private fun noModelMessage(): String {
        val failed = engines.state.value as? dev.akhil.freebuff.inference.LoadState.Failed
        if (failed != null) return failed.message
        return if (!engines.localAvailable()) {
            "No model is loaded, and this build has no local inference engine. " +
                "Open Models to check installation, or rebuild with the native engine enabled."
        } else {
            "No model is loaded. Open Models and load one, then send your message again."
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    fun close() {
        observeJob?.cancel()
        generationJob?.cancel()
    }

    /** Text of the last assistant answer, used by copy and voice playback. */
    fun lastAssistantText(): String =
        _state.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT.name }?.content.orEmpty()

    /** Files attached through the composer are copied into app storage. */
    fun importAttachment(stream: () -> java.io.InputStream, fileName: String): File {
        val dir = File(storage.root, "attachments").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-${fileName.take(60)}")
        stream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return target
    }
}
