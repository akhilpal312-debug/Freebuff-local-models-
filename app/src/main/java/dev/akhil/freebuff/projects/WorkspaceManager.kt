package dev.akhil.freebuff.projects

import dev.akhil.freebuff.core.AppStorage
import dev.akhil.freebuff.core.Ids
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ProjectEntity
import dev.akhil.freebuff.data.db.ProjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class WorkspaceEntry(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/**
 * Owns every project on disk.
 *
 * All paths are resolved through [resolve] which rejects traversal outside the
 * project directory, so neither the agent nor the terminal can reach app data,
 * another project, or shared storage through a crafted relative path.
 */
class WorkspaceManager(
    private val db: AppDatabase,
    private val storage: AppStorage,
) {
    fun observeProjects(): Flow<List<ProjectEntity>> = db.projects().observeAll()

    fun observeProject(id: String): Flow<ProjectEntity?> = db.projects().observe(id)

    suspend fun getProject(id: String): ProjectEntity? = db.projects().get(id)

    suspend fun createProject(
        name: String,
        type: ProjectType,
        template: ProjectTemplate = ProjectTemplates.forType(type),
    ): Result<ProjectEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = name.trim().ifBlank { "Untitled project" }.take(60)
            val id = Ids.short()
            val dirName = dev.akhil.freebuff.core.Format.slug(safeName) + "-" + id
            val dir = File(storage.projectsDir, dirName)
            require(dir.mkdirs()) { "Could not create the project directory." }

            template.files.forEach { (relativePath, contents) ->
                val target = File(dir, relativePath)
                val parent = target.parentFile ?: error("Invalid path $relativePath")
                require(parent.canonicalPath.startsWith(dir.canonicalPath)) { "Invalid template path $relativePath" }
                parent.mkdirs()
                target.writeText(contents)
            }

            val now = System.currentTimeMillis()
            val project = ProjectEntity(
                id = id,
                name = safeName,
                type = type.name,
                directory = dirName,
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = now,
                buildReady = type == ProjectType.KOTLIN_ANDROID || type == ProjectType.COMPOSE_ANDROID,
            )
            db.projects().upsert(project)
            project
        }
    }

    suspend fun renameProject(id: String, name: String) =
        db.projects().rename(id, name.trim().ifBlank { "Untitled project" }.take(60), System.currentTimeMillis())

    suspend fun deleteProject(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val project = db.projects().get(id) ?: error("Unknown project.")
            val dir = projectDir(project)
            if (dir.exists() && dir.canonicalPath.startsWith(storage.projectsDir.canonicalPath)) {
                dir.deleteRecursively()
            }
            db.projects().delete(id)
        }
    }

    suspend fun touch(id: String) = db.projects().touch(id, System.currentTimeMillis())

    fun projectDir(project: ProjectEntity): File = File(storage.projectsDir, project.directory)

    /** Resolves a project-relative path, rejecting anything that escapes the project. */
    fun resolve(project: ProjectEntity, relativePath: String): File {
        val root = projectDir(project).canonicalFile
        val clean = relativePath.trim().trimStart('/')
        val candidate = File(root, clean).canonicalFile
        val rootPath = root.path + File.separator
        require(candidate.path == root.path || candidate.path.startsWith(rootPath)) {
            "Path '$relativePath' is outside the project workspace."
        }
        return candidate
    }

    suspend fun listTree(projectId: String, maxEntries: Int = 400): List<WorkspaceEntry> =
        withContext(Dispatchers.IO) {
            val project = db.projects().get(projectId) ?: return@withContext emptyList()
            val root = projectDir(project)
            if (!root.exists()) return@withContext emptyList()
            val results = mutableListOf<WorkspaceEntry>()
            root.walkTopDown()
                .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
                .forEach { file ->
                    if (results.size >= maxEntries) return@forEach
                    val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (relative.isEmpty()) return@forEach
                    results += WorkspaceEntry(
                        path = relative,
                        isDirectory = file.isDirectory,
                        sizeBytes = if (file.isFile) file.length() else 0,
                        modifiedAt = file.lastModified(),
                    )
                }
            results.sortedWith(compareBy({ !it.isDirectory }, { it.path }))
        }

    suspend fun readFile(projectId: String, relativePath: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val project = db.projects().get(projectId) ?: error("Unknown project.")
                val file = resolve(project, relativePath)
                require(file.exists()) { "File not found: $relativePath" }
                require(file.isFile) { "Not a file: $relativePath" }
                require(file.length() <= 2L * 1024 * 1024) { "File is larger than 2 MB; open it on a computer." }
                file.readText()
            }
        }

    /** Writes a file, snapshotting the previous contents so the change can be undone. */
    suspend fun writeFile(
        projectId: String,
        relativePath: String,
        contents: String,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val project = db.projects().get(projectId) ?: error("Unknown project.")
            val file = resolve(project, relativePath)
            require(!file.isDirectory) { "$relativePath is a directory." }
            file.parentFile?.mkdirs()
            if (file.exists()) snapshot(project, relativePath, file.readText())
            file.writeText(contents)
            db.projects().touch(projectId, System.currentTimeMillis())
            file
        }
    }

    suspend fun deleteFile(projectId: String, relativePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val project = db.projects().get(projectId) ?: error("Unknown project.")
                val file = resolve(project, relativePath)
                require(file != projectDir(project)) { "Refusing to delete the project root." }
                if (file.isFile) snapshot(project, relativePath, runCatching { file.readText() }.getOrDefault(""))
                if (!file.deleteRecursively()) error("Could not delete $relativePath")
            }
        }

    suspend fun createDirectory(projectId: String, relativePath: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val project = db.projects().get(projectId) ?: error("Unknown project.")
                val dir = resolve(project, relativePath)
                require(dir.mkdirs() || dir.isDirectory) { "Could not create directory." }
                dir
            }
        }

    /** Text search across the project (used by the agent's search tool). */
    suspend fun searchInFiles(
        projectId: String,
        needle: String,
        maxResults: Int = 40,
    ): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        if (needle.isBlank()) return@withContext emptyList()
        val project = db.projects().get(projectId) ?: return@withContext emptyList()
        val root = projectDir(project)
        if (!root.exists()) return@withContext emptyList()
        val results = mutableListOf<Pair<String, Int>>()
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile && it.length() < 512 * 1024 && !isBinary(it) }
            .forEach { file ->
                if (results.size >= maxResults) return@forEach
                val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (results.size < maxResults && line.contains(needle, ignoreCase = true)) {
                            results += "$relative:${index + 1}" to index + 1
                        }
                    }
                }
            }
        results
    }

    /** Compact file listing injected into the model prompt for coding modes. */
    suspend fun projectContext(projectId: String, limit: Int = 60): String {
        val project = db.projects().get(projectId) ?: return ""
        val tree = listTree(projectId, maxEntries = 200).filter { !it.isDirectory }
        if (tree.isEmpty()) return ""
        return buildString {
            append(project.name).append(" (").append(project.type).append(")\n")
            tree.take(limit).forEach { entry ->
                append("- ").append(entry.path)
                append(" (").append(dev.akhil.freebuff.core.Format.bytes(entry.sizeBytes)).append(")\n")
            }
            if (tree.size > limit) append("… and ${tree.size - limit} more files\n")
        }.trim()
    }

    private fun isBinary(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(512)
            val read = input.read(buffer)
            if (read <= 0) return@use false
            buffer.take(read).any { it.toInt() == 0 }
        }
    }.getOrDefault(true)

    private fun snapshot(project: ProjectEntity, relativePath: String, previous: String) {
        val historyDir = File(projectDir(project), ".freebuff-history").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val target = File(historyDir, "${relativePath.replace('/', '_')}.$stamp")
        runCatching { target.writeText(previous) }
    }

    /** Restores the most recent snapshot for a file, if one exists. */
    suspend fun undoLastWrite(projectId: String, relativePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val project = db.projects().get(projectId) ?: error("Unknown project.")
                val historyDir = File(projectDir(project), ".freebuff-history")
                val prefix = relativePath.replace('/', '_') + "."
                val snapshot = historyDir.listFiles()
                    ?.filter { it.name.startsWith(prefix) }
                    ?.maxByOrNull { it.name.substringAfterLast('.').toLongOrNull() ?: 0 }
                    ?: error("No earlier version of $relativePath is available.")
                val target = resolve(project, relativePath)
                target.writeText(snapshot.readText())
                snapshot.delete()
                Unit
            }
        }

    /** Zips a project for export; the archive stays app-private and is shared by the user. */
    suspend fun exportProject(projectId: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val project = db.projects().get(projectId) ?: error("Unknown project.")
            val root = projectDir(project)
            require(root.exists()) { "Project files are missing." }
            val archive = File(storage.exportsDir, "${project.directory}.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                root.walkTopDown()
                    .onEnter { it.name != ".freebuff-history" && it.name != "build" && it.name != ".gradle" }
                    .filter { it.isFile }
                    .forEach { file ->
                        val entryName = file.relativeTo(root).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            archive
        }
    }
}
