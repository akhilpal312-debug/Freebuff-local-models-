package dev.akhil.freebuff.ai

import dev.akhil.freebuff.core.Ids
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ConversationEntity
import dev.akhil.freebuff.data.db.MessageEntity
import dev.akhil.freebuff.data.db.MessageRole
import dev.akhil.freebuff.data.db.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

/** Chat persistence: conversations, messages, local-only history. */
class ChatRepository(private val db: AppDatabase) {

    private val lastWrite = AtomicLong(0)

    fun observeConversations(): Flow<List<ConversationEntity>> = db.conversations().observeAll()

    fun search(query: String): Flow<List<ConversationEntity>> =
        if (query.isBlank()) observeConversations() else db.conversations().search(query.trim())

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        db.messages().observe(conversationId)

    fun observeConversation(id: String): Flow<ConversationEntity?> = db.conversations().observe(id)

    suspend fun getConversation(id: String): ConversationEntity? = db.conversations().get(id)

    suspend fun createConversation(modelId: String, mode: AiMode): ConversationEntity {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = Ids.new(),
            title = "New chat",
            modelId = modelId,
            mode = mode.name,
            createdAt = now,
            updatedAt = now,
        )
        db.conversations().upsert(conversation)
        return conversation
    }

    suspend fun mostRecent(): ConversationEntity? = db.conversations().mostRecent()

    suspend fun rename(id: String, title: String) =
        db.conversations().rename(id, title.trim().ifBlank { "Untitled" }.take(80), System.currentTimeMillis())

    suspend fun delete(id: String) = db.conversations().delete(id)

    suspend fun duplicateTitleSuggestion(text: String): String =
        text.trim().replace("\n", " ").take(60).ifBlank { "New chat" }

    suspend fun touch(conversationId: String, modelId: String?, mode: AiMode?) =
        db.conversations().touch(
            conversationId,
            System.currentTimeMillis(),
            modelId,
            mode?.name,
        )

    suspend fun addUserMessage(
        conversationId: String,
        text: String,
        mode: AiMode,
        modelId: String,
        attachments: List<String> = emptyList(),
    ): MessageEntity {
        val existing = db.messages().count(conversationId)
        val message = MessageEntity(
            id = Ids.new(),
            conversationId = conversationId,
            role = MessageRole.USER.name,
            content = text,
            modelId = modelId,
            mode = mode.name,
            createdAt = System.currentTimeMillis(),
            status = MessageStatus.COMPLETE.name,
            attachmentNames = attachments.takeIf { it.isNotEmpty() }?.joinToString("|"),
        )
        db.messages().upsert(message)
        if (existing == 0) {
            val conversation = db.conversations().get(conversationId)
            if (conversation != null && conversation.title == "New chat") {
                db.conversations().rename(
                    conversationId,
                    text.trim().replace("\n", " ").take(52).ifBlank { "New chat" },
                    System.currentTimeMillis(),
                )
            }
        }
        db.conversations().touch(conversationId, System.currentTimeMillis(), modelId, mode.name)
        return message
    }

    suspend fun beginAssistantMessage(
        conversationId: String,
        mode: AiMode,
        modelId: String,
        remote: Boolean,
    ): MessageEntity {
        val message = MessageEntity(
            id = Ids.new(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT.name,
            content = "",
            modelId = modelId,
            mode = mode.name,
            createdAt = System.currentTimeMillis(),
            status = MessageStatus.STREAMING.name,
            fromRemote = remote,
        )
        db.messages().upsert(message)
        return message
    }

    /** Throttled append used by the streaming loop (max ~3 writes per second). */
    suspend fun appendStreamingContent(messageId: String, content: String) {
        val now = System.currentTimeMillis()
        if (now - lastWrite.get() < 320) return
        lastWrite.set(now)
        db.messages().updateContent(messageId, content)
    }

    suspend fun finishMessage(
        messageId: String,
        content: String,
        status: MessageStatus,
        elapsedMs: Long,
        tokens: Int,
        error: String?,
        conversationId: String,
    ) {
        db.messages().finish(messageId, content, status.name, elapsedMs, tokens, error)
        db.conversations().touch(conversationId, System.currentTimeMillis())
    }

    suspend fun messages(conversationId: String): List<MessageEntity> =
        db.messages().list(conversationId)

    suspend fun lastAssistant(conversationId: String): MessageEntity? =
        db.messages().list(conversationId).lastOrNull { it.role == MessageRole.ASSISTANT.name }

    suspend fun deleteMessage(id: String) = db.messages().delete(id)

    /** Editing a user message discards everything after it, then resends. */
    suspend fun truncateAfter(conversationId: String, timestamp: Long) =
        db.messages().deleteAfter(conversationId, timestamp)

    suspend fun clearAll() {
        db.maintenance().clearMessages()
        db.conversations().deleteAll()
    }
}
