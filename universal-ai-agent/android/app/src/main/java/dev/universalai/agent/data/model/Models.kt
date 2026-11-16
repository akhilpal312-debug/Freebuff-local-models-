@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.universalai.agent.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire models for the Universal AI Agent backend. Field names are snake_case on the
// wire; the shared Json instance uses JsonNamingStrategy.SnakeCase.

@Serializable
data class ProviderModel(
    val id: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("context_window") val contextWindow: Int = 8192,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 2048,
    @SerialName("supports_streaming") val supportsStreaming: Boolean = true,
    @SerialName("supports_tools") val supportsTools: Boolean = false,
    @SerialName("supports_vision") val supportsVision: Boolean = false,
    @SerialName("supports_reasoning") val supportsReasoning: Boolean = false,
    val availability: String = "unknown",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("cost_tier") val costTier: String = "medium",
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("provider_slug") val providerSlug: String? = null,
    @SerialName("provider_enabled") val providerEnabled: Boolean = false,
    @SerialName("provider_privacy_tier") val providerPrivacyTier: String = "cloud",
    @SerialName("has_credentials") val hasCredentials: Boolean = false,
)

@Serializable
data class Provider(
    val id: String,
    val slug: String,
    val name: String,
    val kind: String,
    @SerialName("api_compat") val apiCompat: String,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("privacy_tier") val privacyTier: String = "cloud",
    val enabled: Boolean = false,
    @SerialName("is_builtin") val isBuiltin: Boolean = false,
    val notes: String? = null,
    @SerialName("requires_key") val requiresKey: Boolean = true,
    @SerialName("has_credentials") val hasCredentials: Boolean = false,
    @SerialName("credential_fingerprint") val credentialFingerprint: String? = null,
    val models: List<ProviderModel> = emptyList(),
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val mode: String,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val pinned: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("message_count") val messageCount: Int? = null,
)

@Serializable
data class Citation(
    val id: Int,
    val title: String,
    val url: String,
    val snippet: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val verified: Boolean = false,
    @SerialName("referenced") val referenced: Boolean = true,
)

@Serializable
data class Attachment(
    val type: String,
    val url: String,
    val name: String? = null,
)

@Serializable
data class MessageUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val role: String,
    val content: String,
    val sequence: Int = 0,
    val status: String = "complete",
    val error: String? = null,
    @SerialName("model_id") val modelId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val usage: MessageUsage = MessageUsage(),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ConversationDetail(
    val conversation: Conversation,
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class Agent(
    val id: String,
    val kind: String = "general",
    val name: String,
    val description: String? = null,
    val instructions: String = "",
    @SerialName("model_id") val modelId: String? = null,
    val runtime: String = "native",
    @SerialName("permission_profile") val permissionProfile: String = "read_only",
    @SerialName("memory_scope") val memoryScope: String = "agent",
    @SerialName("enabled_tools") val enabledTools: List<String> = emptyList(),
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("is_template") val isTemplate: Boolean = false,
)

@Serializable
data class PlanStep(
    val id: Int,
    val title: String,
    val detail: String = "",
    val tool: String? = null,
)

@Serializable
data class Run(
    val id: String,
    val objective: String,
    val status: String,
    val runtime: String = "native",
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val plan: List<PlanStep> = emptyList(),
    val result: String? = null,
    val error: String? = null,
    @SerialName("tokens_used") val tokensUsed: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
) {
    val isTerminal: Boolean get() = status in setOf("succeeded", "failed", "cancelled")
    val needsApproval: Boolean get() = status == "awaiting_approval"
}

@Serializable
data class RunEvent(
    val seq: Int,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Approval(
    val id: String,
    @SerialName("run_id") val runId: String,
    @SerialName("tool_name") val toolName: String,
    val status: String,
    val reason: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RunDetail(
    val run: Run,
    val approvals: List<Approval> = emptyList(),
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val kind: String = "generic",
    val source: String = "empty",
    @SerialName("repo_url") val repoUrl: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("read_only") val readOnly: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FileEntry(
    val path: String,
    val kind: String,
    val size: Long = 0,
) {
    val isDirectory: Boolean get() = kind == "dir"
    val name: String get() = path.substringAfterLast('/')
}

@Serializable
data class SearchHit(val path: String, val line: Int, val text: String)

@Serializable
data class DiffResult(
    val diff: String = "",
    @SerialName("files_changed") val filesChanged: List<String> = emptyList(),
)

@Serializable
data class CommandResult(
    val command: String,
    @SerialName("exit_code") val exitCode: Int,
    val ok: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("duration_ms") val durationMs: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class Doc(
    val id: String,
    val title: String,
    val kind: String = "note",
    @SerialName("content_md") val contentMd: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val excerpt: String? = null,
)

@Serializable
data class ResearchSource(
    val id: Int,
    val title: String,
    val url: String,
    val snippet: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val verified: Boolean = false,
    val excerpt: String? = null,
)

@Serializable
data class ResearchResult(
    val query: String,
    val searched: Boolean,
    val providers: List<String> = emptyList(),
    val sources: List<ResearchSource> = emptyList(),
    val citations: List<Citation> = emptyList(),
    @SerialName("report_md") val reportMd: String,
    val synthesis: String = "extractive",
    val model: String? = null,
    @SerialName("synthesis_error") val synthesisError: String? = null,
    val errors: List<String> = emptyList(),
    @SerialName("duration_ms") val durationMs: Int = 0,
    val document: Doc? = null,
)

@Serializable
data class RuntimeConnection(
    val id: String,
    val runtime: String = "openclaw",
    val label: String,
    @SerialName("gateway_url") val gatewayUrl: String,
    @SerialName("device_id") val deviceId: String,
    val status: String,
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    @SerialName("has_token") val hasToken: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
)

@Serializable
data class ConnectionProbe(
    val ok: Boolean,
    val message: String,
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    val scopes: List<String> = emptyList(),
    @SerialName("latency_ms") val latencyMs: Int = 0,
    @SerialName("pairing_request_id") val pairingRequestId: String? = null,
)

@Serializable
data class ConnectionResponse(
    val connection: RuntimeConnection,
    val probe: ConnectionProbe? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    val requests: Int = 0,
    @SerialName("average_latency_ms") val averageLatencyMs: Int = 0,
)

@Serializable
data class ConnectionTest(
    val ok: Boolean,
    val status: String,
    val message: String,
    @SerialName("latency_ms") val latencyMs: Int = 0,
    @SerialName("model_count") val modelCount: Int? = null,
)

@Serializable
data class ToolInfo(
    val name: String,
    val description: String = "",
    @SerialName("requires_approval") val requiresApproval: Boolean = false,
)

@Serializable
data class Profile(
    val id: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("default_model_id") val defaultModelId: String? = null,
)
