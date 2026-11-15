package dev.akhil.freebuff.terminal

import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ProjectEntity
import dev.akhil.freebuff.data.db.TerminalEntryEntity
import dev.akhil.freebuff.projects.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class TerminalResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val deniedReason: String? = null,
    val needsConfirmation: Boolean = false,
    val durationMs: Long = 0,
) {
    val ok: Boolean get() = deniedReason == null && exitCode == 0 && !needsConfirmation
}

private data class CommandSpec(
    val binary: String,
    val description: String,
    val destructive: Boolean = false,
    val validator: (List<String>) -> String? = { null },
)

/**
 * A deliberately small, allowlisted command runner.
 *
 * Design constraints, in order of importance:
 *  1. No shell. Commands are executed directly with an argument vector, so a
 *     crafted string can never inject a second command.
 *  2. The working directory is always a project inside the app sandbox, and
 *     every path argument must resolve inside it.
 *  3. Destructive commands require an explicit confirmation from the user.
 *  4. Fixed timeouts and an output ceiling keep a runaway process from hanging
 *     or filling memory.
 *
 * Commands that Android simply does not provide (java, gradle, python, npm) are
 * reported as unavailable instead of being faked.
 */
class TerminalRunner(
    private val db: AppDatabase,
    private val workspace: WorkspaceManager,
) {
    private val activeProcess = AtomicReference<Process?>(null)

    private val specs: Map<String, CommandSpec> = listOf(
        CommandSpec("ls", "list directory contents"),
        CommandSpec("cat", "print a file"),
        CommandSpec("head", "first lines of a file"),
        CommandSpec("tail", "last lines of a file"),
        CommandSpec("wc", "count lines, words and bytes"),
        CommandSpec("grep", "search text in files"),
        CommandSpec("find", "find files by name"),
        CommandSpec("du", "estimate directory size"),
        CommandSpec("date", "current date and time"),
        CommandSpec("mkdir", "create a directory"),
        CommandSpec("touch", "create an empty file"),
        CommandSpec("cp", "copy a file", destructive = false),
        CommandSpec("mv", "move or rename", destructive = true),
        CommandSpec("rm", "delete files", destructive = true),
    ).associateBy { it.binary }

    private val knownToolsThatAreMissing = mapOf(
        "java" to "Android has no JDK. Build Android projects in Android Studio.",
        "gradle" to "No Gradle runtime on Android. Use Build Mode to see what this device can do.",
        "python" to "No Python interpreter on Android. Export the project and run it on a computer.",
        "python3" to "No Python interpreter on Android.",
        "node" to "No Node.js runtime on Android.",
        "npm" to "No Node.js runtime on Android.",
        "git" to "Git is not part of Android. Project history is kept by the app instead.",
        "sh" to "Shell execution is disabled. Only allowlisted commands run.",
        "bash" to "Shell execution is disabled. Only allowlisted commands run.",
    )

    fun sessionEntries(sessionId: String): Flow<List<TerminalEntryEntity>> =
        db.terminal().observe(sessionId)

    /**
     * The last [limit] commands typed in [sessionId], newest first.
     *
     * Reads on the IO dispatcher because a Room query on the caller's thread
     * would throw (and would block the UI). Any database failure degrades to an
     * empty list — command recall is a convenience, never a reason to crash.
     */
    fun history(sessionId: String, limit: Int = 80): List<String> {
        if (sessionId.isBlank() || limit <= 0) return emptyList()
        return runCatching {
            runBlocking(Dispatchers.IO) {
                db.terminal().recent(sessionId, limit).map { it.command }
            }
        }.getOrDefault(emptyList())
    }

    fun cancelActive() {
        activeProcess.getAndSet(null)?.let { process ->
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
        }
    }

    fun availableCommands(): List<Pair<String, String>> = specs.entries
        .map { it.key to it.value.description }
        .sortedBy { it.first }

    suspend fun run(
        project: ProjectEntity,
        sessionId: String,
        commandLine: String,
        confirmed: Boolean = false,
        timeoutSeconds: Long = 30,
    ): TerminalResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) {
            return@withContext TerminalResult(trimmed, "", 0)
        }
        val tokens = tokenize(trimmed)
        val binary = tokens.firstOrNull()?.lowercase().orEmpty()

        knownToolsThatAreMissing[binary]?.let { reason ->
            return@withContext finish(
                project,
                sessionId,
                trimmed,
                "Not available: $reason",
                127,
                startedAt,
                deniedReason = reason,
            )
        }

        // A few things are answered in-process because Android has no shell builtin.
        when (binary) {
            "pwd" -> return@withContext finish(project, sessionId, trimmed, workspace.projectDir(project).absolutePath, 0, startedAt)
            "echo" -> return@withContext finish(project, sessionId, trimmed, tokens.drop(1).joinToString(" "), 0, startedAt)
            "help", "?" -> return@withContext finish(
                project,
                sessionId,
                trimmed,
                availableCommands().joinToString("\n") { "  ${it.first.padEnd(8)} ${it.second}" },
                0,
                startedAt,
            )
            "clear" -> {
                db.terminal().clear(sessionId)
                return@withContext TerminalResult(trimmed, "", 0)
            }
        }

        val spec = specs[binary] ?: return@withContext finish(
            project,
            sessionId,
            trimmed,
            "Command not allowed: '${tokens.first()}'. Type 'help' for the allowlist.",
            126,
            startedAt,
            deniedReason = "Not in the command allowlist.",
        )

        if (spec.destructive && !confirmed) {
            return@withContext TerminalResult(
                command = trimmed,
                output = "This command can delete or move files. Confirm to run it.",
                exitCode = 0,
                needsConfirmation = true,
            )
        }

        val pathError = validateArguments(project, spec.binary, tokens.drop(1))
        if (pathError != null) {
            return@withContext finish(project, sessionId, trimmed, pathError, 126, startedAt, deniedReason = pathError)
        }

        val executable = File("/system/bin/${spec.binary}")
        if (!executable.exists()) {
            return@withContext finish(
                project,
                sessionId,
                trimmed,
                "The system does not provide '${spec.binary}' on this device.",
                127,
                startedAt,
                deniedReason = "Binary missing on this device.",
            )
        }

        val process = try {
            ProcessBuilder(listOf(executable.absolutePath) + tokens.drop(1))
                .directory(workspace.projectDir(project))
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = project.directory
                    environment()["TMPDIR"] = File(project.directory).absolutePath
                }
                .start()
        } catch (error: Exception) {
            return@withContext finish(
                project,
                sessionId,
                trimmed,
                "Could not start the process: ${error.message}",
                1,
                startedAt,
            )
        }

        activeProcess.set(process)
        val output = StringBuilder()
        val timedOut = withTimeoutOrNull(timeoutSeconds * 1000) {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    if (output.length < MAX_OUTPUT_CHARS) {
                        output.appendRange(buffer, 0, minOf(read, MAX_OUTPUT_CHARS - output.length))
                    }
                }
            }
            process.waitFor(2, TimeUnit.SECONDS)
            true
        } ?: run {
            process.destroyForcibly()
            false
        }
        activeProcess.compareAndSet(process, null)

        val exitCode = if (timedOut) runCatching { process.exitValue() }.getOrDefault(0) else 124
        val body = buildString {
            append(output.toString().ifBlank { if (timedOut) "" else "(no output)" })
            if (!timedOut) append("\n[stopped after ${timeoutSeconds}s]")
            if (output.length >= MAX_OUTPUT_CHARS) append("\n[output truncated at ${MAX_OUTPUT_CHARS / 1024} KB]")
        }
        finish(project, sessionId, trimmed, body, exitCode, startedAt)
    }

    /** Every path-like argument must stay inside the project workspace. */
    private fun validateArguments(project: ProjectEntity, binary: String, args: List<String>): String? {
        val root = workspace.projectDir(project).canonicalFile
        for (arg in args) {
            if (arg.startsWith("-")) continue
            if (arg.contains('\u0000')) return "Invalid argument."
            val looksLikePath = arg.contains('/') || arg == "." || arg == ".." || arg.startsWith("~")
            val isFlagPattern = binary == "grep" && arg.matches(Regex("[A-Za-z0-9_\\-. ]+")) &&
                !looksLikePath && args.size >= 2 && args.first() == arg
            if (isFlagPattern) continue
            if (!looksLikePath) continue
            if (arg.startsWith("~")) return "Home-relative paths are not permitted."
            if (arg.startsWith("/")) return "Absolute paths are not permitted here: $arg"
            val resolved = File(root, arg).canonicalFile
            val rootPath = root.path + File.separator
            if (resolved.path != root.path && !resolved.path.startsWith(rootPath)) {
                return "Path escapes the project workspace: $arg"
            }
        }
        if (binary == "rm") {
            val targets = args.filterNot { it.startsWith("-") }
            if (targets.isEmpty()) return "rm needs a target."
            if (targets.any { it == "." || it == "./" || it == "*" }) {
                return "Refusing to delete the whole project. Name the files explicitly."
            }
        }
        return null
    }

    private suspend fun finish(
        project: ProjectEntity,
        sessionId: String,
        command: String,
        output: String,
        exitCode: Int,
        startedAt: Long,
        deniedReason: String? = null,
    ): TerminalResult {
        val finishedAt = System.currentTimeMillis()
        if (deniedReason == null) {
            db.terminal().insert(
                TerminalEntryEntity(
                    id = dev.akhil.freebuff.core.Ids.new(),
                    sessionId = sessionId,
                    command = command,
                    output = output,
                    exitCode = exitCode,
                    cwd = project.name,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                ),
            )
        }
        return TerminalResult(
            command = command,
            output = output,
            exitCode = exitCode,
            deniedReason = deniedReason,
            durationMs = finishedAt - startedAt,
        )
    }

    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (char in line) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                quote != null -> if (char == quote) quote = null else current.append(char)
                char == '"' || char == '\'' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
