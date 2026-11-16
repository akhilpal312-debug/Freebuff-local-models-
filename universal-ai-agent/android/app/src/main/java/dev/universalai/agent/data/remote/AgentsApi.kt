package dev.universalai.agent.data.remote

import dev.universalai.agent.data.model.Agent
import dev.universalai.agent.data.model.Approval
import dev.universalai.agent.data.model.Run
import dev.universalai.agent.data.model.RunDetail
import dev.universalai.agent.data.model.RunEvent
import dev.universalai.agent.data.model.ToolInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class AgentsApi(private val client: ApiClient) {

    suspend fun agents(): List<Agent> {
        val body = client.get("/api/v1/agents", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Agent.serializer()), body["agents"] ?: return emptyList(),
        )
    }

    suspend fun templates(): List<Agent> {
        val body = client.get("/api/v1/agents/templates", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Agent.serializer()), body["templates"] ?: return emptyList(),
        )
    }

    suspend fun createAgent(
        name: String,
        kind: String,
        instructions: String,
        modelId: String?,
        runtime: String,
        permissionProfile: String,
        memoryScope: String,
        enabledTools: List<String>,
        projectId: String?,
        templateName: String? = null,
    ): Agent {
        val payload = buildJsonObject {
            put("name", name)
            put("kind", kind)
            put("instructions", instructions)
            put("runtime", runtime)
            put("permission_profile", permissionProfile)
            put("memory_scope", memoryScope)
            putJsonArray("enabled_tools") { enabledTools.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            modelId?.let { put("model_id", it) }
            projectId?.let { put("project_id", it) }
            templateName?.let { put("template_name", it) }
        }
        return client.post("/api/v1/agents", payload.toString(), Agent.serializer())
    }

    suspend fun updateAgent(id: String, payload: JsonObject): Agent =
        client.patch("/api/v1/agents/$id", payload.toString(), Agent.serializer())

    suspend fun deleteAgent(id: String) = client.delete("/api/v1/agents/$id")

    suspend fun tools(): List<ToolInfo> {
        val body = client.get("/api/v1/tools", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(ToolInfo.serializer()), body["tools"] ?: return emptyList(),
        )
    }

    suspend fun runs(limit: Int = 30): List<Run> {
        val body = client.get("/api/v1/runs?limit=$limit", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Run.serializer()), body["runs"] ?: return emptyList(),
        )
    }

    suspend fun run(id: String): RunDetail = client.get("/api/v1/runs/$id", RunDetail.serializer())

    suspend fun createRun(
        objective: String,
        agentId: String? = null,
        projectId: String? = null,
        runtime: String? = null,
        modelId: String? = null,
        permissionProfile: String? = null,
        enabledTools: List<String>? = null,
    ): Run {
        val payload = buildJsonObject {
            put("objective", objective)
            agentId?.let { put("agent_id", it) }
            projectId?.let { put("project_id", it) }
            runtime?.let { put("runtime", it) }
            modelId?.let { put("model_id", it) }
            permissionProfile?.let { put("permission_profile", it) }
            enabledTools?.let { tools ->
                putJsonArray("enabled_tools") {
                    tools.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
            }
        }
        return client.post("/api/v1/runs", payload.toString(), Run.serializer())
    }

    suspend fun cancelRun(id: String): Run =
        client.post("/api/v1/runs/$id/cancel", "{}", Run.serializer())

    suspend fun approvals(status: String = "pending"): List<Approval> {
        val body = client.get("/api/v1/approvals?status=$status", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Approval.serializer()), body["approvals"] ?: return emptyList(),
        )
    }

    suspend fun decideApproval(id: String, approved: Boolean, reason: String? = null): Approval {
        val payload = buildJsonObject {
            put("approved", approved)
            reason?.let { put("reason", it) }
        }
        return client.post("/api/v1/approvals/$id", payload.toString(), Approval.serializer())
    }

    /**
     * Live run events. Reconnecting with `after` resumes from the last seen `seq`, so
     * a dropped connection does not lose progress.
     */
    fun events(runId: String, after: Int = 0): Flow<RunEvent> =
        client.sse("/api/v1/runs/$runId/events?after=$after", "{}").mapNotNull { event ->
            val json = runCatching {
                ApiClient.json.parseToJsonElement(event.data) as? JsonObject
            }.getOrNull() ?: return@mapNotNull null
            val seq = json["seq"]?.toString()?.toIntOrNull() ?: return@mapNotNull null
            RunEvent(
                seq = seq,
                type = json.str("type") ?: "event",
                payload = json.obj("payload") ?: JsonObject(emptyMap()),
                createdAt = json.str("created_at"),
            )
        }
}
