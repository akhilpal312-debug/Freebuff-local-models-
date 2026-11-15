package dev.akhil.freebuff.agent

import dev.akhil.freebuff.ai.AiMode
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.core.Ids
import dev.akhil.freebuff.core.Notifications
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ProjectType
import dev.akhil.freebuff.data.db.StepStatus
import dev.akhil.freebuff.data.db.TaskEntity
import dev.akhil.freebuff.data.db.TaskStatus
import dev.akhil.freebuff.data.db.TaskStepEntity
import dev.akhil.freebuff.inference.ChatTurn
import dev.akhil.freebuff.inference.EngineManager
import dev.akhil.freebuff.inference.GenEvent
import dev.akhil.freebuff.inference.GenParams
import dev.akhil.freebuff.inference.GenRequest
import dev.akhil.freebuff.projects.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class TaskIntent {
    CREATE_WEBSITE,
    CREATE_ANDROID_APP,
    EDIT_PROJECT,
    EXPLAIN,
    PLAN_ONLY,
    NEEDS_CLARIFICATION,
}

/** A step the orchestrator will actually be able to carry out and verify. */
data class PlannedStep(
    val title: String,
    val kind: StepKind,
    val detail: String = "",
)

enum class StepKind { CREATE_PROJECT, GENERATE_FILE, CHECK, VERIFY, REPORT }

/**
 * Runs multi-step work in bounded, verifiable stages.
 *
 * The model is used for *content* (code, prose). Project creation, file writes,
 * checks and the completion verdict are done by this class, because a 0.5B model
 * cannot be trusted to report its own success. A task only ends COMPLETED when
 * every step finished and the verification step passed; otherwise it is FAILED
 * with the real reason.
 */
class TaskOrchestrator(
    private val db: AppDatabase,
    private val workspace: WorkspaceManager,
    private val engines: EngineManager,
    private val scope: CoroutineScope,
    private val appContext: android.content.Context,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun observeTasks(): Flow<List<TaskEntity>> = db.tasks().observeAll()

    fun observeTask(id: String): Flow<TaskEntity?> = db.tasks().observe(id)

    fun observeSteps(id: String): Flow<List<TaskStepEntity>> = db.taskSteps().observe(id)

    suspend fun getTask(id: String): TaskEntity? = db.tasks().get(id)

    /** Records the request immediately, then runs it in the background. */
    suspend fun submit(
        request: String,
        mode: AiMode,
        projectId: String?,
        modelId: String,
    ): TaskEntity {
        val task = TaskEntity(
            id = Ids.new(),
            title = request.trim().replace("\n", " ").take(64).ifBlank { "Task" },
            request = request.trim(),
            mode = mode.name,
            modelId = modelId,
            projectId = projectId,
            status = TaskStatus.QUEUED.name,
            createdAt = System.currentTimeMillis(),
        )
        db.tasks().upsert(task)
        start(task)
        return task
    }

    fun start(task: TaskEntity) {
        if (jobs.containsKey(task.id)) return
        val job = scope.launch {
            runTask(task)
            jobs.remove(task.id)
        }
        jobs[task.id] = job
    }

    fun cancel(taskId: String) {
        jobs[taskId]?.cancel()
        jobs.remove(taskId)
        scope.launch {
            db.tasks().finish(
                id = taskId,
                status = TaskStatus.CANCELLED.name,
                finishedAt = System.currentTimeMillis(),
                report = "Cancelled before completion. Partial files, if any, remain in the project.",
                verification = "Not verified — cancelled.",
                created = "",
                modified = "",
                tests = "",
                artifact = null,
                error = "Cancelled by user.",
                logs = "",
                verified = false,
            )
        }
    }

    fun retry(task: TaskEntity) {
        scope.launch {
            db.tasks().upsert(
                task.copy(
                    status = TaskStatus.QUEUED.name,
                    startedAt = 0,
                    finishedAt = 0,
                    report = "",
                    verification = "",
                    error = null,
                    verified = false,
                ),
            )
            db.taskSteps().deleteForTask(task.id)
            start(task)
        }
    }

    // ---------------------------------------------------------------------

    private suspend fun runTask(task: TaskEntity) {
        val startedAt = System.currentTimeMillis()
        db.tasks().markRunning(task.id, TaskStatus.RUNNING.name, startedAt)
        val log = StringBuilder()

        val filesBefore = snapshotFiles(task.projectId)
        var createdProjectId = task.projectId
        var verified = false
        var failure: String? = null
        val testsRun = mutableListOf<String>()

        try {
            val intent = IntentParser.classify(task.request)
            val plan = IntentParser.plan(intent, task.request)
            if (plan.isEmpty()) {
                failure = IntentParser.explain(intent, task.request)
                finish(task, TaskStatus.FAILED, log.toString(), failure, verified = false, created = emptyList(), modified = emptyList(), tests = testsRun, artifact = null)
                return
            }

            plan.forEachIndexed { index, step ->
                val stepEntity = TaskStepEntity(
                    id = Ids.new(),
                    taskId = task.id,
                    position = index,
                    title = step.title,
                    detail = step.detail,
                    status = StepStatus.RUNNING.name,
                    startedAt = System.currentTimeMillis(),
                )
                db.taskSteps().upsert(stepEntity)
                log.append("▶ ${step.title}\n")

                val outcome = try {
                    executeStep(task, intent, step, createdProjectId, testsRun, log)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    StepOutcome(false, error.message ?: error.javaClass.simpleName)
                }

                if (outcome.projectId != null) createdProjectId = outcome.projectId

                if (outcome.ok) {
                    db.taskSteps().update(
                        stepEntity.id,
                        StepStatus.DONE.name,
                        outcome.detail,
                        System.currentTimeMillis(),
                    )
                    log.append("  ✓ ${outcome.detail}\n")
                } else {
                    db.taskSteps().update(
                        stepEntity.id,
                        StepStatus.FAILED.name,
                        outcome.detail,
                        System.currentTimeMillis(),
                    )
                    log.append("  ✗ ${outcome.detail}\n")
                    failure = "${step.title}: ${outcome.detail}"
                    finish(
                        task,
                        TaskStatus.FAILED,
                        log.toString(),
                        failure,
                        verified = false,
                        created = emptyList(),
                        modified = emptyList(),
                        tests = testsRun,
                        artifact = null,
                        projectId = createdProjectId,
                    )
                    return
                }
            }

            val filesAfter = snapshotFiles(createdProjectId)
            val created = (filesAfter - filesBefore).sorted()
            val modified = filesBefore.intersect(filesAfter).sorted()
            verified = created.isNotEmpty() || modified.isNotEmpty()

            finish(
                task = task,
                status = TaskStatus.COMPLETED,
                log = log.toString(),
                failure = null,
                verified = verified,
                created = created,
                modified = modified,
                tests = testsRun,
                artifact = createdProjectId,
                projectId = createdProjectId,
            )
        } catch (cancelled: CancellationException) {
            db.tasks().finish(
                task.id,
                TaskStatus.CANCELLED.name,
                System.currentTimeMillis(),
                "Cancelled while running.",
                "Not verified.",
                "", "", "", null, "Cancelled", log.toString(), false,
            )
        } catch (error: Exception) {
            finish(
                task,
                TaskStatus.FAILED,
                log.toString(),
                error.message ?: error.javaClass.simpleName,
                verified = false,
                created = emptyList(),
                modified = emptyList(),
                tests = testsRun,
                artifact = null,
                projectId = createdProjectId,
            )
        }
    }

    private data class StepOutcome(
        val ok: Boolean,
        val detail: String,
        val projectId: String? = null,
    )

    private suspend fun executeStep(
        task: TaskEntity,
        intent: TaskIntent,
        step: PlannedStep,
        projectId: String?,
        testsRun: MutableList<String>,
        log: StringBuilder,
    ): StepOutcome {
        return when (step.kind) {
            StepKind.CREATE_PROJECT -> {
                val type = when (intent) {
                    TaskIntent.CREATE_WEBSITE -> ProjectType.HTML
                    TaskIntent.CREATE_ANDROID_APP -> ProjectType.COMPOSE_ANDROID
                    else -> ProjectType.EMPTY
                }
                val name = IntentParser.projectName(task.request)
                workspace.createProject(name, type).fold(
                    onSuccess = { project ->
                        StepOutcome(true, "Created project \"${project.name}\" (${type.name}).", project.id)
                    },
                    onFailure = { StepOutcome(false, it.message ?: "Could not create the project.") },
                )
            }

            StepKind.GENERATE_FILE -> {
                if (projectId == null) {
                    return StepOutcome(false, "No project is attached to this task.")
                }
                val filePath = IntentParser.targetFile(intent, task.request)
                val content = askModel(task, step, filePath)
                    ?: return StepOutcome(
                        false,
                        "The model returned no usable file content. Nothing was written.",
                    )
                val cleaned = ContentExtractor.extract(content, filePath)
                if (cleaned.isBlank()) {
                    return StepOutcome(false, "The generated file was empty after cleanup.")
                }
                workspace.writeFile(projectId, filePath, cleaned).fold(
                    onSuccess = {
                        log.append("  wrote $filePath (${cleaned.length} chars)\n")
                        StepOutcome(true, "Wrote $filePath (${cleaned.length} characters).")
                    },
                    onFailure = { StepOutcome(false, it.message ?: "Could not write $filePath") },
                )
            }

            StepKind.CHECK -> {
                if (projectId == null) return StepOutcome(false, "No project to check.")
                testsRun += step.title
                when (intent) {
                    TaskIntent.CREATE_WEBSITE -> {
                        val project = workspace.getProject(projectId)
                            ?: return StepOutcome(false, "Project record disappeared.")
                        val index = workspace.resolve(project, "index.html")
                        if (!index.exists()) return StepOutcome(false, "index.html was not created.")
                        val html = index.readText()
                        if (!html.contains("<html", ignoreCase = true)) {
                            return StepOutcome(false, "index.html is missing an <html> element.")
                        }
                        val referenced = Regex("(?:src|href)=\"([^\"]+)\"")
                            .findAll(html)
                            .map { it.groupValues[1] }
                            .filterNot { it.startsWith("http") || it.startsWith("#") || it.startsWith("data:") }
                            .toList()
                        val missing = referenced.filterNot { workspace.resolve(project, it).exists() }
                        if (missing.isNotEmpty()) {
                            return StepOutcome(false, "Referenced files are missing: ${missing.joinToString(", ")}")
                        }
                        StepOutcome(true, "index.html parsed; all ${referenced.size} local references resolve.")
                    }

                    TaskIntent.CREATE_ANDROID_APP -> {
                        val project = workspace.getProject(projectId)
                            ?: return StepOutcome(false, "Project record disappeared.")
                        val manifest = workspace.resolve(project, "app/src/main/AndroidManifest.xml")
                        if (!manifest.exists()) return StepOutcome(false, "AndroidManifest.xml is missing.")
                        val parseError = runCatching {
                            val parser = android.util.Xml.newPullParser()
                            parser.setInput(manifest.inputStream(), null)
                            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                                parser.next()
                            }
                            null
                        }.getOrElse { it.message ?: "Manifest is not valid XML." }
                        if (parseError != null) return StepOutcome(false, "Manifest XML error: $parseError")
                        val gradle = workspace.resolve(project, "app/build.gradle.kts")
                        if (!gradle.exists()) return StepOutcome(false, "app/build.gradle.kts is missing.")
                        val kotlinFiles = workspace.listTree(projectId)
                            .filter { it.path.endsWith(".kt") }
                        if (kotlinFiles.isEmpty()) return StepOutcome(false, "No Kotlin sources were generated.")
                        StepOutcome(
                            true,
                            "Manifest XML valid, Gradle script present, ${kotlinFiles.size} Kotlin file(s). " +
                                "Not compiled — see Build Mode for the on-device limitation.",
                        )
                    }

                    else -> StepOutcome(true, "Nothing to check for this intent.")
                }
            }

            StepKind.VERIFY -> {
                if (projectId == null) return StepOutcome(false, "No project to verify.")
                val files = workspace.listTree(projectId).filterNot { it.isDirectory }
                if (files.isEmpty()) return StepOutcome(false, "The project contains no files.")
                StepOutcome(
                    true,
                    "Verified ${files.size} file(s) on disk, ${Format.bytes(files.sumOf { it.sizeBytes })} total.",
                )
            }

            StepKind.REPORT -> {
                if (projectId == null) return StepOutcome(false, "No project to report on.")
                val files = workspace.listTree(projectId).filterNot { it.isDirectory }
                StepOutcome(
                    true,
                    "Report ready: ${files.size} files. " +
                        if (intent == TaskIntent.CREATE_ANDROID_APP) {
                            "Open Build Mode to see whether this device can compile it."
                        } else {
                            "Open the project to preview or export it."
                        },
                )
            }
        }
    }

    /** One bounded model call. Returns null when no engine is loaded or it fails. */
    private suspend fun askModel(task: TaskEntity, step: PlannedStep, filePath: String): String? {
        val active = engines.active.value ?: return null
        val engine = engines.engineFor(active.remote)
        val mode = AiMode.fromName(task.mode)
        val prompt = buildString {
            appendLine(dev.akhil.freebuff.ai.SystemPrompts.forMode(mode))
            appendLine()
            appendLine("Task: ${task.request}")
            appendLine("Step: ${step.title}")
            appendLine("Write the complete contents of $filePath.")
            appendLine("Reply with one code block only. No commentary.")
        }
        val request = GenRequest(
            turns = listOf(ChatTurn(ChatTurn.ROLE_SYSTEM, prompt), ChatTurn(ChatTurn.ROLE_USER, step.detail.ifBlank { task.request })),
            params = GenParams(maxTokens = 768, temperature = 0.4f, contextTokens = active.contextTokens),
        )
        val builder = StringBuilder()
        withTimeoutOrNull(180_000) {
            engine.stream(request).collect { event ->
                when (event) {
                    is GenEvent.Token -> if (builder.length < 40_000) builder.append(event.text)
                    else -> Unit
                }
            }
        }
        return builder.toString().ifBlank { null }
    }

    private suspend fun snapshotFiles(projectId: String?): Set<String> {
        if (projectId == null) return emptySet()
        return workspace.listTree(projectId).filterNot { it.isDirectory }.map { it.path }.toSet()
    }

    private suspend fun finish(
        task: TaskEntity,
        status: TaskStatus,
        log: String,
        failure: String?,
        verified: Boolean,
        created: List<String>,
        modified: List<String>,
        tests: List<String>,
        artifact: String?,
        projectId: String? = null,
    ) {
        val report = buildString {
            appendLine("# ${task.title}")
            appendLine()
            appendLine("Request: ${task.request}")
            appendLine("Mode: ${task.mode}")
            appendLine("Status: ${status.name}")
            appendLine("Finished: ${Format.date(System.currentTimeMillis())}")
            appendLine()
            if (failure != null) {
                appendLine("## Failure")
                appendLine(failure)
                appendLine()
            }
            if (created.isNotEmpty()) {
                appendLine("## Files created")
                created.forEach { appendLine("- $it") }
                appendLine()
            }
            if (modified.isNotEmpty() && status == TaskStatus.COMPLETED) {
                appendLine("## Files present")
                appendLine("${modified.size} file(s) were part of the project before this task.")
                appendLine()
            }
            if (tests.isNotEmpty()) {
                appendLine("## Checks performed")
                tests.distinct().forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("## Limitations")
            appendLine(
                if (verified) {
                    "Files were written and checked on disk. This device cannot compile Kotlin or run " +
                        "Gradle, so compilation is not verified here."
                } else {
                    "Nothing was verified on disk."
                },
            )
        }
        val verification = if (verified) {
            "Verified on disk: ${created.size} new file(s)."
        } else {
            "Not verified: ${failure ?: "no files produced"}."
        }
        db.tasks().finish(
            id = task.id,
            status = status.name,
            finishedAt = System.currentTimeMillis(),
            report = report,
            verification = verification,
            created = created.joinToString("\n"),
            modified = modified.joinToString("\n"),
            tests = tests.distinct().joinToString("\n"),
            artifact = artifact,
            error = failure,
            logs = log.take(20_000),
            verified = verified,
        )
        runCatching {
            Notifications.taskFinished(
                appContext,
                if (status == TaskStatus.COMPLETED) "Task completed" else "Task ${status.name.lowercase()}",
                task.title,
            )
        }
    }
}

/** Turns free text into a bounded plan without asking a 0.5B model to reason. */
object IntentParser {

    fun classify(request: String): TaskIntent {
        val text = request.lowercase()
        return when {
            listOf("android app", "android application", "apk", "compose app", "kotlin app")
                .any { text.contains(it) } -> TaskIntent.CREATE_ANDROID_APP
            listOf("website", "web page", "webpage", "landing page", "html site", "portfolio site")
                .any { text.contains(it) } -> TaskIntent.CREATE_WEBSITE
            listOf("explain", "what does", "how does", "review").any { text.contains(it) } -> TaskIntent.EXPLAIN
            listOf("plan", "roadmap", "steps to").any { text.contains(it) } -> TaskIntent.PLAN_ONLY
            listOf("edit", "fix", "change", "add to", "refactor").any { text.contains(it) } -> TaskIntent.EDIT_PROJECT
            else -> TaskIntent.NEEDS_CLARIFICATION
        }
    }

    fun plan(intent: TaskIntent, request: String): List<PlannedStep> = when (intent) {
        TaskIntent.CREATE_WEBSITE -> listOf(
            PlannedStep("Create project folder", StepKind.CREATE_PROJECT),
            PlannedStep("Generate index.html", StepKind.GENERATE_FILE, request),
            PlannedStep("Check page structure", StepKind.CHECK),
            PlannedStep("Verify files on disk", StepKind.VERIFY),
            PlannedStep("Write report", StepKind.REPORT),
        )

        TaskIntent.CREATE_ANDROID_APP -> listOf(
            PlannedStep("Create Android project skeleton", StepKind.CREATE_PROJECT),
            PlannedStep("Generate MainActivity.kt", StepKind.GENERATE_FILE, request),
            PlannedStep("Validate manifest and Gradle files", StepKind.CHECK),
            PlannedStep("Verify files on disk", StepKind.VERIFY),
            PlannedStep("Write report", StepKind.REPORT),
        )

        TaskIntent.EDIT_PROJECT -> listOf(
            PlannedStep("Generate updated file", StepKind.GENERATE_FILE, request),
            PlannedStep("Verify files on disk", StepKind.VERIFY),
            PlannedStep("Write report", StepKind.REPORT),
        )

        else -> emptyList()
    }

    fun explain(intent: TaskIntent, request: String): String = when (intent) {
        TaskIntent.NEEDS_CLARIFICATION ->
            "This request does not name something the agent can execute. Say what to build " +
                "(for example \"create a website for a bakery\") or which project to edit."
        TaskIntent.EXPLAIN ->
            "Explanation requests are answered in Chat, where the model can respond directly."
        TaskIntent.PLAN_ONLY ->
            "Planning requests belong in Chat with Plan mode selected."
        else -> "Nothing to execute."
    }

    fun projectName(request: String): String {
        val cleaned = request.replace(Regex("(?i)^(create|make|build|generate)\\s+(a|an|the)?\\s*"), "")
        return cleaned.replace("\n", " ").take(40).trim().ifBlank { "New project" }
            .replaceFirstChar { it.uppercase() }
    }

    fun targetFile(intent: TaskIntent, request: String): String = when (intent) {
        TaskIntent.CREATE_WEBSITE -> "index.html"
        TaskIntent.CREATE_ANDROID_APP -> "app/src/main/java/dev/akhil/app/MainActivity.kt"
        else -> {
            val path = Regex("([\\w./-]+\\.(kt|kts|java|html|css|js|py|md|xml))").find(request)?.value
            path ?: "NOTES.md"
        }
    }
}

/** Pulls source code out of a model reply, tolerating small-model formatting noise. */
object ContentExtractor {

    fun extract(raw: String, filePath: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val fenceRegex = Regex("```[a-zA-Z0-9+#-]*\\n([\\s\\S]*?)```")
        val fenced = fenceRegex.findAll(text).map { it.groupValues[1] }.toList()
        val language = filePath.substringAfterLast('.', "")
        val best = when {
            fenced.isEmpty() -> text
            language == "html" -> fenced.maxByOrNull { block ->
                (if (block.contains("<html", true)) 100 else 0) + block.length
            } ?: text
            else -> fenced.maxByOrNull { it.length } ?: text
        }
        return best.trim()
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
