package dev.universalai.agent.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.universalai.agent.core.AppContainer
import dev.universalai.agent.core.AppResult
import dev.universalai.agent.data.model.Attachment
import dev.universalai.agent.data.model.ChatMessage
import dev.universalai.agent.data.model.Conversation
import dev.universalai.agent.data.model.ProviderModel
import dev.universalai.agent.data.remote.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

data class ChatUiState(
    val loading: Boolean = true,
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val mode: String = "chat",
    val modelId: String? = null,
    val reasoningEffort: String = "medium",
    val models: List<ProviderModel> = emptyList(),
    val streaming: Boolean = false,
    val streamingText: String = "",
    val routingNote: String? = null,
    val notice: String? = null,
    val error: String? = null,
    val pendingAttachments: List<Attachment> = emptyList(),
    val editingMessageId: String? = null,
)

class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    var state by mutableStateOf(ChatUiState())
        private set

    private var streamJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val detail = container.chat.conversation(conversationId)
            val models = container.models.models()
            val conversation = detail.valueOrNull()?.conversation
            state = state.copy(
                loading = false,
                conversation = conversation,
                messages = detail.valueOrNull()?.messages.orEmpty(),
                mode = conversation?.mode ?: state.mode,
                modelId = conversation?.modelId,
                models = models.valueOrNull().orEmpty(),
                error = (detail as? AppResult.Failure)?.message,
                notice = null,
            )
        }
    }

    fun onInputChange(value: String) {
        state = state.copy(input = value)
    }

    fun selectMode(mode: String) {
        if (mode == state.mode) return
        state = state.copy(mode = mode, notice = null)
        viewModelScope.launch { container.chat.setMode(conversationId, mode) }
    }

    fun selectModel(modelId: String?) {
        state = state.copy(modelId = modelId)
    }

    fun setReasoningEffort(effort: String) {
        state = state.copy(reasoningEffort = effort)
    }

    fun addAttachment(attachment: Attachment) {
        if (state.pendingAttachments.size >= 3) {
            state = state.copy(error = "Up to 3 images per message.")
            return
        }
        state = state.copy(pendingAttachments = state.pendingAttachments + attachment, error = null)
    }

    fun removeAttachment(name: String?) {
        state = state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.name == name })
    }

    fun reportAttachmentError(message: String) {
        state = state.copy(error = message)
    }

    fun send() {
        val content = state.input.trim()
        if (state.streaming) return
        if (content.isEmpty() && state.pendingAttachments.isEmpty()) return
        val editingId = state.editingMessageId
        val attachments = state.pendingAttachments
        val optimistic = ChatMessage(
            id = "local-${System.currentTimeMillis()}",
            conversationId = conversationId,
            role = "user",
            content = content,
            attachments = attachments,
        )
        state = state.copy(
            input = "",
            pendingAttachments = emptyList(),
            editingMessageId = null,
            streaming = true,
            streamingText = "",
            error = null,
            notice = null,
            messages = if (editingId == null) state.messages + optimistic else state.messages,
        )
        val flow = if (editingId != null) {
            container.chat.edit(conversationId, editingId, content, state.mode)
        } else {
            container.chat.stream(
                conversationId = conversationId,
                content = content,
                mode = state.mode,
                modelId = state.modelId,
                reasoningEffort = state.reasoningEffort.takeIf { state.mode == "deep" },
                attachments = attachments,
            )
        }
        consume(flow)
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        state = state.copy(streaming = false)
        viewModelScope.launch {
            delay(600)
            load()
            state = state.copy(notice = "Generation stopped.")
        }
    }

    fun regenerate() {
        if (state.streaming) return
        state = state.copy(streaming = true, streamingText = "", error = null, notice = null)
        consume(container.chat.regenerate(conversationId))
    }

    fun beginEdit(message: ChatMessage) {
        if (state.streaming) return
        state = state.copy(input = message.content, editingMessageId = message.id)
    }

    fun cancelEdit() {
        state = state.copy(editingMessageId = null, input = "")
    }

    fun dismissError() {
        state = state.copy(error = null)
    }

    private fun consume(flow: Flow<ChatStreamEvent>) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            try {
                flow.collect { event ->
                    when (event) {
                        is ChatStreamEvent.Meta -> {
                            state = state.copy(
                                routingNote = "${event.modelName} · ${event.providerName}",
                            )
                        }
                        is ChatStreamEvent.Delta -> {
                            state = state.copy(streamingText = state.streamingText + event.text)
                        }
                        ChatStreamEvent.ReasoningProgress -> Unit
                        is ChatStreamEvent.Done -> {
                            state = state.copy(streaming = false, streamingText = "")
                            refreshMessages()
                        }
                        is ChatStreamEvent.ServerError -> {
                            state = state.copy(streaming = false, error = event.message)
                            refreshMessages()
                        }
                        ChatStreamEvent.Cancelled -> {
                            state = state.copy(streaming = false, streamingText = "")
                            refreshMessages()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                state = state.copy(
                    streaming = false,
                    error = error.message ?: "Generation failed.",
                )
            }
        }
    }

    private fun refreshMessages() {
        viewModelScope.launch {
            val result = container.chat.conversation(conversationId)
            val detail = result.valueOrNull() ?: return@launch
            state = state.copy(
                conversation = detail.conversation,
                messages = detail.messages,
            )
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
