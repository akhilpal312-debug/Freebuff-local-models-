package dev.universalai.agent.data.remote

import dev.universalai.agent.data.model.ConnectionTest
import dev.universalai.agent.data.model.Provider
import dev.universalai.agent.data.model.ProviderModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class CustomModelInput(
    val modelId: String,
    val displayName: String? = null,
    val contextWindow: Int = 8192,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
)

class ModelsApi(private val client: ApiClient) {

    suspend fun providers(): List<Provider> {
        val body = client.get("/api/v1/providers", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Provider.serializer()),
            body["providers"] ?: return emptyList(),
        )
    }

    suspend fun models(): List<ProviderModel> {
        val body = client.get("/api/v1/models", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(ProviderModel.serializer()),
            body["models"] ?: return emptyList(),
        )
    }

    suspend fun createCustomProvider(
        name: String,
        baseUrl: String,
        apiCompat: String,
        privacyTier: String,
        apiKey: String?,
        models: List<CustomModelInput>,
    ): Provider {
        val payload = buildJsonObject {
            put("name", name)
            put("base_url", baseUrl)
            put("api_compat", apiCompat)
            put("privacy_tier", privacyTier)
            put("enabled", true)
            apiKey?.takeIf { it.isNotBlank() }?.let { put("api_key", it) }
            putJsonArray("models") {
                models.forEach { model ->
                    add(
                        buildJsonObject {
                            put("model_id", model.modelId)
                            model.displayName?.let { put("display_name", it) }
                            put("context_window", model.contextWindow)
                            put("supports_tools", model.supportsTools)
                            put("supports_vision", model.supportsVision)
                            put("supports_reasoning", model.supportsReasoning)
                        },
                    )
                }
            }
        }
        return client.post("/api/v1/providers", payload.toString(), Provider.serializer())
    }

    suspend fun updateProvider(
        id: String,
        enabled: Boolean? = null,
        baseUrl: String? = null,
        notes: String? = null,
    ): Provider {
        val payload = buildJsonObject {
            enabled?.let { put("enabled", it) }
            baseUrl?.let { put("base_url", it) }
            notes?.let { put("notes", it) }
        }
        return client.patch("/api/v1/providers/$id", payload.toString(), Provider.serializer())
    }

    suspend fun deleteProvider(id: String) = client.delete("/api/v1/providers/$id")

    suspend fun setCredentials(id: String, apiKey: String) {
        client.put(
            "/api/v1/providers/$id/credentials",
            buildJsonObject { put("api_key", apiKey) }.toString(),
            JsonObject.serializer(),
        )
    }

    suspend fun deleteCredentials(id: String) = client.delete("/api/v1/providers/$id/credentials")

    suspend fun testConnection(id: String): ConnectionTest =
        client.post("/api/v1/providers/$id/test", "{}", ConnectionTest.serializer())

    suspend fun syncModels(id: String): JsonObject = client.post(
        "/api/v1/providers/$id/sync", "{}", JsonObject.serializer(),
    )

    suspend fun setDefaultModel(modelId: String): ProviderModel =
        client.post("/api/v1/models/$modelId/default", "{}", ProviderModel.serializer())

    suspend fun updateModel(
        modelId: String,
        supportsTools: Boolean? = null,
        supportsVision: Boolean? = null,
        supportsReasoning: Boolean? = null,
        contextWindow: Int? = null,
    ): ProviderModel {
        val payload = buildJsonObject {
            supportsTools?.let { put("supports_tools", it) }
            supportsVision?.let { put("supports_vision", it) }
            supportsReasoning?.let { put("supports_reasoning", it) }
            contextWindow?.let { put("context_window", it) }
        }
        return client.patch("/api/v1/models/$modelId", payload.toString(), ProviderModel.serializer())
    }
}
