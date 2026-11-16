package dev.universalai.agent.data.remote

import dev.universalai.agent.data.model.ConnectionTest
import dev.universalai.agent.data.model.ConnectionResponse
import dev.universalai.agent.data.model.Profile
import dev.universalai.agent.data.model.RuntimeConnection
import dev.universalai.agent.data.model.Usage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RuntimeApi(private val client: ApiClient) {

    suspend fun connections(): List<RuntimeConnection> {
        val body = client.get("/api/v1/runtime/connections", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(RuntimeConnection.serializer()), body["connections"] ?: return emptyList(),
        )
    }

    suspend fun connect(
        gatewayUrl: String,
        token: String?,
        label: String = "OpenClaw Gateway",
    ): ConnectionResponse = client.post(
        "/api/v1/runtime/connections",
        buildJsonObject {
            put("gateway_url", gatewayUrl)
            token?.takeIf { it.isNotBlank() }?.let { put("token", it) }
            put("label", label)
        }.toString(),
        ConnectionResponse.serializer(),
    )

    suspend fun test(id: String): ConnectionTest =
        client.post("/api/v1/runtime/connections/$id/test", "{}", ConnectionTest.serializer())

    suspend fun disconnect(id: String) = client.delete("/api/v1/runtime/connections/$id")

    suspend fun status(): JsonObject = client.get("/api/v1/runtime/status", JsonObject.serializer())
}

class AccountApi(private val client: ApiClient) {

    suspend fun me(): Profile = client.get("/api/v1/me", Profile.serializer())

    suspend fun usage(): Usage = client.get("/api/v1/usage", Usage.serializer())
}
