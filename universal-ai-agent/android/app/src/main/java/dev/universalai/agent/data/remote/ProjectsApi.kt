package dev.universalai.agent.data.remote

import dev.universalai.agent.data.model.CommandResult
import dev.universalai.agent.data.model.DiffResult
import dev.universalai.agent.data.model.Doc
import dev.universalai.agent.data.model.FileEntry
import dev.universalai.agent.data.model.Project
import dev.universalai.agent.data.model.SearchHit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class FileContent(val path: String, val content: String)

class ProjectsApi(private val client: ApiClient) {

    suspend fun list(): List<Project> {
        val body = client.get("/api/v1/projects", JsonObject.serializer())
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(Project.serializer()), body["projects"] ?: return emptyList(),
        )
    }

    suspend fun create(name: String, kind: String = "generic", description: String? = null): Project {
        val payload = buildJsonObject {
            put("name", name)
            put("kind", kind)
            description?.let { put("description", it) }
        }
        return client.post("/api/v1/projects", payload.toString(), Project.serializer())
    }

    suspend fun update(id: String, readOnly: Boolean? = null, name: String? = null): Project {
        val payload = buildJsonObject {
            readOnly?.let { put("read_only", it) }
            name?.let { put("name", it) }
        }
        return client.patch("/api/v1/projects/$id", payload.toString(), Project.serializer())
    }

    suspend fun delete(id: String) = client.delete("/api/v1/projects/$id")

    suspend fun importGithub(repoUrl: String, name: String? = null, branch: String? = null): Project {
        val payload = buildJsonObject {
            put("repo_url", repoUrl)
            name?.let { put("name", it) }
            branch?.let { put("branch", it) }
        }
        val body = client.post(
            "/api/v1/projects/import/github", payload.toString(), JsonObject.serializer(),
        )
        return ApiClient.json.decodeFromJsonElement(
            Project.serializer(), body["project"] ?: throw ApiException(500, "decode", "Missing project."),
        )
    }

    suspend fun importZip(name: String, fileName: String, bytes: ByteArray): Project {
        val body = client.upload(
            "/api/v1/projects/import/zip",
            fields = mapOf("name" to name),
            fileField = "file",
            fileName = fileName,
            bytes = bytes,
            JsonObject.serializer(),
        )
        return ApiClient.json.decodeFromJsonElement(
            Project.serializer(), body["project"] ?: throw ApiException(500, "decode", "Missing project."),
        )
    }

    suspend fun files(projectId: String, path: String = "."): List<FileEntry> {
        val body = client.get(
            "/api/v1/projects/$projectId/files?path=${encode(path)}", JsonObject.serializer(),
        )
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(FileEntry.serializer()), body["entries"] ?: return emptyList(),
        )
    }

    suspend fun readFile(projectId: String, path: String): FileContent {
        val body = client.get(
            "/api/v1/projects/$projectId/file?path=${encode(path)}", JsonObject.serializer(),
        )
        return FileContent(path, body.str("content").orEmpty())
    }

    suspend fun writeFile(projectId: String, path: String, content: String): JsonObject =
        client.put(
            "/api/v1/projects/$projectId/file",
            buildJsonObject {
                put("path", path)
                put("content", content)
            }.toString(),
            JsonObject.serializer(),
        )

    suspend fun search(projectId: String, query: String): List<SearchHit> {
        val body = client.post(
            "/api/v1/projects/$projectId/search",
            buildJsonObject { put("query", query) }.toString(),
            JsonObject.serializer(),
        )
        return ApiClient.json.decodeFromJsonElement(
            ListSerializer(SearchHit.serializer()), body["hits"] ?: return emptyList(),
        )
    }

    suspend fun gitStatus(projectId: String): Pair<Boolean, String> {
        val body = client.get(
            "/api/v1/projects/$projectId/git/status", JsonObject.serializer(),
        )
        return (body["is_repo"]?.jsonPrimitive?.booleanOrNull == true) to body.str("status").orEmpty()
    }

    suspend fun gitDiff(projectId: String): DiffResult =
        client.get("/api/v1/projects/$projectId/git/diff", DiffResult.serializer())

    suspend fun runCommand(projectId: String, command: String): CommandResult =
        client.post(
            "/api/v1/projects/$projectId/commands",
            buildJsonObject { put("command", command) }.toString(),
            CommandResult.serializer(),
        )

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
