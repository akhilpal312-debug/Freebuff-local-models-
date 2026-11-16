package dev.universalai.agent.data.remote

import dev.universalai.agent.data.model.Doc
import dev.universalai.agent.data.model.ResearchResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KnowledgeApi(private val client: ApiClient) {

    suspend fun documents(query: String? = null, projectId: String? = null): List<Doc> {
        val params = buildList {
            query?.takeIf { it.isNotBlank() }?.let { add("query=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            projectId?.let { add("project_id=$it") }
        }.joinToString("&")
        val path = "/api/v1/documents" + if (params.isBlank()) "" else "?$params"
        val body = client.get(path, JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Doc.serializer()), body["documents"] ?: return emptyList(),
        )
    }

    suspend fun document(id: String): Doc = client.get("/api/v1/documents/$id", Doc.serializer())

    suspend fun create(
        title: String,
        contentMd: String,
        kind: String = "note",
        projectId: String? = null,
    ): Doc = client.post(
        "/api/v1/documents",
        buildJsonObject {
            put("title", title)
            put("content_md", contentMd)
            put("kind", kind)
            projectId?.let { put("project_id", it) }
        }.toString(),
        Doc.serializer(),
    )

    suspend fun update(id: String, title: String? = null, contentMd: String? = null): Doc {
        val payload = buildJsonObject {
            title?.let { put("title", it) }
            contentMd?.let { put("content_md", it) }
        }
        return client.patch("/api/v1/documents/$id", payload.toString(), Doc.serializer())
    }

    suspend fun delete(id: String) = client.delete("/api/v1/documents/$id")

    suspend fun summarise(id: String, modelId: String? = null): String {
        val payload = buildJsonObject { modelId?.let { put("model_id", it) } }
        val body = client.post(
            "/api/v1/documents/$id/summarise", payload.toString(), JsonObject.serializer(),
        )
        return body.str("summary_md").orEmpty()
    }

    suspend fun research(query: String, modelId: String? = null, save: Boolean = false): ResearchResult =
        client.post(
            "/api/v1/research",
            buildJsonObject {
                put("query", query)
                put("save_as_document", save)
                modelId?.let { put("model_id", it) }
            }.toString(),
            ResearchResult.serializer(),
        )
}
