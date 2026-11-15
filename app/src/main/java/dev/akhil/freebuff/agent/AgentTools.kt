package dev.akhil.freebuff.agent

import dev.akhil.freebuff.projects.WorkspaceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Tools the agent may request. Nothing here can leave the project workspace. */
enum class AgentTool(val id: String, val description: String, val needsApproval: Boolean) {
    LIST_FILES("list_files", "List files in the project", false),
    READ_FILE("read_file", "Read a text file", false),
    WRITE_FILE("write_file", "Create or overwrite a file", true),
    SEARCH_FILES("search_files", "Search file contents", false),
    RUN_TESTS("run_tests", "Run the project's test command", true),
    DELETE_FILE("delete_file", "Delete a file", true),
    ;

    companion object {
        fun fromId(id: String): AgentTool? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

data class ToolCall(val tool: AgentTool, val args: JsonObject, val raw: String)

data class ToolResult(
    val ok: Boolean,
    val summary: String,
    val detail: String = "",
) {
    fun observation(): String = (if (ok) "OK: " else "ERROR: ") + summary +
        (if (detail.isNotBlank()) "\n$detail" else "")
}

/**
 * Parses and executes tool requests.
 *
 * The model proposes; this class decides. A malformed request, an unknown tool,
 * a path outside the workspace or a missing approval all fail loudly rather than
 * being quietly skipped.
 */
class ToolRouter(private val workspace: WorkspaceManager) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Finds `TOOL <name> {json}` request lines in model output. */
    fun parseRequests(modelOutput: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        val regex = Regex("(?im)^\\s*TOOL\\s+([a-z_]+)\\s*(\\{.*\\})?\\s*$")
        modelOutput.lineSequence().forEach { line ->
            val match = regex.find(line) ?: return@forEach
            val name = match.groupValues[1]
            val tool = AgentTool.fromId(name) ?: return@forEach
            val argText = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "{}"
            val args = runCatching { json.parseToJsonElement(argText) as JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
            results += ToolCall(tool, args, line.trim())
        }
        return results
    }

    fun hasToolRequests(modelOutput: String): Boolean = parseRequests(modelOutput).isNotEmpty()

    suspend fun execute(projectId: String, call: ToolCall): ToolResult {
        return when (call.tool) {
            AgentTool.LIST_FILES -> {
                val directory = stringArg(call, "path", "")
                val tree = workspace.listTree(projectId, maxEntries = 200)
                    .filter { directory.isBlank() || it.path == directory || it.path.startsWith("$directory/") }
                if (tree.isEmpty()) {
                    ToolResult(true, "No files found${if (directory.isBlank()) "" else " in $directory"}.", "")
                } else {
                    ToolResult(
                        true,
                        "${tree.size} entries",
                        tree.take(120).joinToString("\n") {
                            (if (it.isDirectory) "dir  " else "file ") + it.path
                        },
                    )
                }
            }

            AgentTool.READ_FILE -> {
                val path = stringArg(call, "path")
                if (path.isBlank()) return ToolResult(false, "read_file needs a path.")
                workspace.readFile(projectId, path).fold(
                    onSuccess = { content ->
                        val clipped = if (content.length > 6000) {
                            content.take(6000) + "\n… [truncated, ${content.length} chars total]"
                        } else {
                            content
                        }
                        ToolResult(true, "Read $path (${content.length} chars)", clipped)
                    },
                    onFailure = { ToolResult(false, it.message ?: "Could not read $path") },
                )
            }

            AgentTool.WRITE_FILE -> {
                val path = stringArg(call, "path")
                val content = stringArg(call, "content")
                if (path.isBlank()) return ToolResult(false, "write_file needs a path.")
                if (path.endsWith("/")) return ToolResult(false, "write_file needs a file path, not a directory.")
                workspace.writeFile(projectId, path, content).fold(
                    onSuccess = { file ->
                        ToolResult(true, "Wrote ${file.relativeTo(workspace.projectDir(requireProject(projectId))).path} (${content.length} chars)")
                    },
                    onFailure = { ToolResult(false, it.message ?: "Could not write $path") },
                )
            }

            AgentTool.SEARCH_FILES -> {
                val needle = stringArg(call, "query").ifBlank { stringArg(call, "pattern") }
                if (needle.isBlank()) return ToolResult(false, "search_files needs a query.")
                val hits = workspace.searchInFiles(projectId, needle)
                if (hits.isEmpty()) {
                    ToolResult(true, "No matches for \"$needle\".", "")
                } else {
                    ToolResult(true, "${hits.size} matches for \"$needle\".", hits.joinToString("\n") { it.first })
                }
            }

            AgentTool.DELETE_FILE -> {
                val path = stringArg(call, "path")
                if (path.isBlank()) return ToolResult(false, "delete_file needs a path.")
                workspace.deleteFile(projectId, path).fold(
                    onSuccess = { ToolResult(true, "Deleted $path") },
                    onFailure = { ToolResult(false, it.message ?: "Could not delete $path") },
                )
            }

            AgentTool.RUN_TESTS -> ToolResult(
                ok = false,
                summary = "No test runner is available for this project on Android.",
                detail = "Android provides no JDK, Gradle, Node or Python runtime, so tests cannot be " +
                    "executed on the device. Export the project to run its tests on a computer.",
            )
        }
    }

    private suspend fun requireProject(projectId: String) =
        workspace.getProject(projectId) ?: error("Unknown project")

    private fun stringArg(call: ToolCall, name: String, default: String = ""): String =
        call.args[name]?.jsonPrimitive?.contentOrNull ?: default
}
