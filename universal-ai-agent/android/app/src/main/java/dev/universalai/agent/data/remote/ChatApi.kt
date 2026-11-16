package dev.universalai.agent.data.remote

import dev.universalai.agent.data.model.Attachment
import dev.universalai.agent.data.model.ChatMessage
import dev.universalai.agent.data.model.Conversation
import dev.universalai.agent.data.model.ConversationDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

sealed interface ChatStreamEvent {
    data class Meta(
        val conversationId: String,
        val messageId: String,
        val modelName: String,
        val providerName: String,
        val routingReason: String,
        val reasoningEffort: String?,
    ) : ChatStreamEvent

    data class Delta(val text: String) : ChatStreamEvent
    data object ReasoningProgress : ChatStreamEvent
    data class Done(val messageId: String, val latencyMs: Int) : ChatStreamEvent
    data class ServerError(val message: String) : ChatStreamEvent
    data object Cancelled : ChatStreamEvent
}

class ChatApi(private val client: ApiClient) {

    suspend fun list(limit: Int = 50): List<Conversation> {
        val body = client.get("/api/v1/conversations?limit=$limit", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Conversation.serializer()),
            body["conversations"] ?: return emptyList(),
        )
    }

    suspend fun create(
        mode: String,
        title: String? = null,
        modelId: String? = null,
        projectId: String? = null,
    ): Conversation {
        val payload = buildJsonObject {
            put("mode", mode)
            title?.let { put("title", it) }
            modelId?.let { put("model_id", it) }
            projectId?.let { put("project_id", it) }
        }
        return client.post("/api/v1/conversations", payload.toString(), Conversation.serializer())
    }

    suspend fun detail(id: String): ConversationDetail =
        client.get("/api/v1/conversations/$id", ConversationDetail.serializer())

    suspend fun messages(id: String): List<ChatMessage> {
        val body = client.get(
            "/api/v1/conversations/$id/messages", JsonObject.serializer(),
        )
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(ChatMessage.serializer()),
            body["messages"] ?: return emptyList(),
        )
    }

    suspend fun rename(id: String, title: String) {
        client.patch(
            "/api/v1/conversations/$id",
            buildJsonObject { put("title", title) }.toString(),
            Conversation.serializer(),
        )
    }

    suspend fun setMode(id: String, mode: String) {
        client.patch(
            "/api/v1/conversations/$id",
            buildJsonObject { put("mode", mode) }.toString(),
            Conversation.serializer(),
        )
    }

    suspend fun delete(id: String) = client.delete("/api/v1/conversations/$id")

    fun stream(
        conversationId: String,
        content: String,
        mode: String,
        modelId: String? = null,
        reasoningEffort: String? = null,
        attachments: List<Attachment> = emptyList(),
    ): Flow<ChatStreamEvent> {
        val payload = buildJsonObject {
            put("content", content)
            put("mode", mode)
            modelId?.let { put("model_id", it) }
            reasoningEffort?.let { put("reasoning_effort", it) }
            putJsonArray("attachments") {
                attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("type", attachment.type)
                            put("url", attachment.url)
                            attachment.name?.let { put("name", it) }
                        },
                    )
                }
            }
        }
        return client.sse("/api/v1/conversations/$conversationId/stream", payload.toString())
            .map { parseEvent(it) }
    }

    fun regenerate(conversationId: String): Flow<ChatStreamEvent> =
        client.sse("/api/v1/conversations/$conversationId/regenerate", "{}")
            .map { parseEvent(it) }

    fun edit(
        conversationId: String,
        messageId: String,
        content: String,
        mode: String? = null,
        modelId: String? = null,
    ): Flow<ChatStreamEvent> {
        val payload = buildJsonObject {
            put("content", content)
            mode?.let { put("mode", it) }
            modelId?.let { put("model_id", it) }
        }
        return client.sse(
            "/api/v1/conversations/$conversationId/messages/$messageId/edit",
            payload.toString(),
        ).map { parseEvent(it) }
    }

    private fun parseEvent(event: SseEvent): ChatStreamEvent {
        val json = client.decodeEvent(event) ?: return ChatStreamEvent.ServerError("Malformed stream event.")
        return when (json.str("type")) {
            "meta" -> ChatStreamEvent.Meta(
                conversationId = json.str("conversation_id").orEmpty(),
                messageId = json.str("message_id").orEmpty(),
                modelName = json.obj("model")?.str("display_name") ?: "model",
                providerName = json.obj("provider")?.str("name") ?: "provider",
                routingReason = json.str("routing_reason").orEmpty(),
                reasoningEffort = json.str("reasoning_effort"),
            )
            "delta" -> ChatStreamEvent.Delta(json.str("text").orEmpty())
            "reasoning_progress" -> ChatStreamEvent.ReasoningProgress
            "done" -> ChatStreamEvent.Done(
                messageId = json.str("message_id").orEmpty(),
                latencyMs = json.int("latency_ms"),
            )
            "cancelled" -> ChatStreamEvent.Cancelled
            "error" -> ChatStreamEvent.ServerError(json.str("message") ?: "Generation failed.")
            else -> ChatStreamEvent.Delta("")
        }
    }
}
