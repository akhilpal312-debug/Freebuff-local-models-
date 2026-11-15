package dev.akhil.freebuff.build

import android.content.Context
import dev.akhil.freebuff.core.AppStorage
import dev.akhil.freebuff.core.DeviceInspector
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.core.Ids
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.BuildRecordEntity
import dev.akhil.freebuff.data.db.ProjectEntity
import dev.akhil.freebuff.projects.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class ToolProbe(val name: String, val found: Boolean, val detail: String)

/** What this specific device can and cannot do about building Android apps. */
data class BuildCapabilities(
    val jdk: ToolProbe,
    val androidSdk: ToolProbe,
    val gradle: ToolProbe,
    val freeStorage: Long,
    val availableRamMb: Long,
    val canBuildLocally: Boolean,
    val blockers: List<String>,
)

data class BuildOutcome(
    val success: Boolean,
    val log: String,
    val artifact: File?,
    val message: String,
)

/**
 * On-device Android build pipeline.
 *
 * Android ships no JDK, no Android SDK and no Gradle, and an app sandbox cannot
 * spawn a JVM build even on rooted devices with Termux. Rather than streaming
 * progress bars that build nothing, this class probes the real environment and
 * reports exactly what is missing. When a real toolchain *is* present (a future
 * bundled toolchain, or an OEM image that provides one), the same code path runs
 * Gradle through the allowlisted process runner.
 *
 * Nothing is ever uploaded to a remote builder without explicit consent.
 */
class BuildRunner(
    private val context: Context,
    private val db: AppDatabase,
    private val workspace: WorkspaceManager,
    private val storage: AppStorage,
) {
    fun observeBuilds(): Flow<List<BuildRecordEntity>> = db.builds().observeAll()

    fun observeProjectBuilds(projectId: String): Flow<List<BuildRecordEntity>> =
        db.builds().observeForProject(projectId)

    fun capabilities(): BuildCapabilities {
        val profile = DeviceInspector.inspect(context)
        val jdk = probeJava()
        val sdk = probeAndroidSdk()
        val gradle = probeGradle()
        val blockers = buildList {
            if (!jdk.found) add("No JDK: ${jdk.detail}")
            if (!sdk.found) add("No Android SDK: ${sdk.detail}")
            if (!gradle.found) add("No Gradle: ${gradle.detail}")
            if (profile.freeStorageBytes < 1_500L * 1024 * 1024) {
                add("Under 1.5 GB free storage; a Gradle build needs several GB.")
            }
            if (profile.totalRamMb < 3000) {
                add("${profile.totalRamMb} MB RAM is below the practical minimum for a Gradle build.")
            }
        }
        return BuildCapabilities(
            jdk = jdk,
            androidSdk = sdk,
            gradle = gradle,
            freeStorage = profile.freeStorageBytes,
            availableRamMb = profile.totalRamMb,
            canBuildLocally = blockers.isEmpty(),
            blockers = blockers,
        )
    }

    private fun probeJava(): ToolProbe {
        val candidates = listOf(
            "/system/bin/java",
            "/system/xbin/java",
            "/data/local/tmp/jdk/bin/java",
            System.getenv("JAVA_HOME")?.let { "$it/bin/java" },
        ).filterNotNull()
        val found = candidates.firstOrNull { File(it).canExecute() }
        return if (found != null) {
            ToolProbe("JDK", true, found)
        } else {
            ToolProbe(
                "JDK",
                false,
                "Android has no java executable in the app sandbox. A JDK must be bundled or " +
                    "the build must run on a computer.",
            )
        }
    }

    private fun probeAndroidSdk(): ToolProbe {
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val platforms = sdkRoot?.let { File(it, "platforms") }
        return if (platforms?.exists() == true) {
            ToolProbe("Android SDK", true, platforms.absolutePath)
        } else {
            ToolProbe(
                "Android SDK",
                false,
                "No platforms/ directory is reachable from the app sandbox; android.jar and " +
                    "aapt2 are required to compile and package.",
            )
        }
    }

    private fun probeGradle(): ToolProbe {
        val candidates = listOf("/system/bin/gradle", "/data/local/tmp/gradle/bin/gradle")
        val found = candidates.firstOrNull { File(it).canExecute() }
        return if (found != null) {
            ToolProbe("Gradle", true, found)
        } else {
            ToolProbe("Gradle", false, "No Gradle distribution on this device.")
        }
    }

    /** Validates a produced artifact instead of trusting a build log. */
    fun verifyArtifact(file: File): Result<String> = runCatching {
        require(file.exists()) { "Artifact does not exist: ${file.name}" }
        require(file.length() > 0) { "Artifact is empty: ${file.name}" }
        when (file.extension.lowercase()) {
            "apk" -> {
                require(file.length() > 1024) { "APK is implausibly small (${file.length()} bytes)." }
                java.util.zip.ZipFile(file).use { zip ->
                    val names = zip.entries().toList().map { it.name }
                    require(names.contains("AndroidManifest.xml")) { "APK has no AndroidManifest.xml." }
                    require(names.any { it == "classes.dex" || it.startsWith("classes") }) {
                        "APK contains no dex code."
                    }
                    "APK verified: ${Format.bytes(file.length())}, ${names.size} entries, " +
                        "manifest + dex present."
                }
            }
            "aab" -> {
                java.util.zip.ZipFile(file).use { zip ->
                    val names = zip.entries().toList().map { it.name }
                    require(names.any { it == "BundleConfig.pb" }) { "AAB is missing BundleConfig.pb." }
                    "AAB verified: ${Format.bytes(file.length())}, ${names.size} entries."
                }
            }
            else -> "File exists: ${file.name} (${Format.bytes(file.length())})."
        }
    }

    /** Prepares a project for Gradle by pinning memory settings for a low-RAM host. */
    suspend fun prepareGradleMemory(project: ProjectEntity, heapMb: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val gradleProperties = workspace.resolve(project, "gradle.properties")
                val current = if (gradleProperties.exists()) gradleProperties.readText() else ""
                val updated = current.lines()
                    .filterNot { it.startsWith("org.gradle.jvmargs") }
                    .joinToString("\n")
                    .trimEnd() + "\norg.gradle.jvmargs=-Xmx${heapMb}m -XX:MaxMetaspaceSize=512m\n"
                workspace.writeFile(project.id, "gradle.properties", updated).getOrThrow()
                Unit
            }
        }

    /**
     * Attempts a local build. Returns an honest outcome: either a verified
     * artifact, or the precise list of missing tools.
     */
    suspend fun build(project: ProjectEntity, variant: String = "debug"): BuildOutcome =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val caps = capabilities()
            val record = BuildRecordEntity(
                id = Ids.new(),
                projectId = project.id,
                variant = variant,
                status = "RUNNING",
                command = "gradlew assemble${variant.replaceFirstChar { it.uppercase() }}",
                logPath = File(storage.logsDir, "build-${project.id}-$startedAt.log").absolutePath,
                artifactPath = null,
                startedAt = startedAt,
            )
            db.builds().insert(record)

            if (!caps.canBuildLocally) {
                val log = buildString {
                    appendLine("Build not started — this device cannot compile Android projects.")
                    appendLine()
                    caps.blockers.forEach { appendLine("• $it") }
                    appendLine()
                    appendLine("What works instead:")
                    appendLine("• Export the project as a zip and open it in Android Studio.")
                    appendLine("• Every generated file is real source you can build on a computer.")
                    appendLine()
                    appendLine("Memory: ${caps.availableRamMb} MB total RAM")
                    appendLine("Storage free: ${Format.bytes(caps.freeStorage)}")
                }
                File(record.logPath).writeText(log)
                db.builds().update(
                    record.copy(status = "UNSUPPORTED", finishedAt = System.currentTimeMillis(), artifactPath = null),
                )
                return@withContext BuildOutcome(
                    success = false,
                    log = log,
                    artifact = null,
                    message = "This device has no JDK/Android SDK/Gradle, so no APK was produced. " +
                        "The project can be exported and built on a computer.",
                )
            }

            val wrapper = workspace.resolve(project, "gradlew")
            val gradleCommand = if (wrapper.exists()) wrapper.absolutePath else "/system/bin/gradle"
            val logBuilder = StringBuilder()
            val started = runCatching {
                val process = ProcessBuilder(
                    gradleCommand,
                    "assemble${variant.replaceFirstChar { it.uppercase() }}",
                )
                    .directory(workspace.projectDir(project))
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (logBuilder.length < 200_000) logBuilder.appendLine(line)
                }
                process.waitFor()
            }

            val exit = started.getOrNull() ?: -1
            val candidate = listOf(
                "app/build/outputs/apk/$variant/app-$variant.apk",
                "app/build/outputs/bundle/$variant/app-$variant.aab",
            ).map { File(workspace.projectDir(project), it) }.firstOrNull { it.exists() }

            val verification = candidate?.let { verifyArtifact(it) }
            val success = exit == 0 && verification?.isSuccess == true
            File(record.logPath).writeText(
                logBuilder.toString() + "\n" +
                    (verification?.fold({ "VERIFIED: $it" }, { "VERIFY FAILED: ${it.message}" })
                        ?: "No artifact produced."),
            )
            db.builds().update(
                record.copy(
                    status = if (success) "SUCCEEDED" else "FAILED",
                    finishedAt = System.currentTimeMillis(),
                    artifactPath = candidate?.absolutePath,
                    artifactBytes = candidate?.length() ?: 0,
                    verified = success,
                ),
            )
            BuildOutcome(
                success = success,
                log = logBuilder.toString(),
                artifact = candidate,
                message = when {
                    success -> verification!!.getOrThrow()
                    exit != 0 -> "Gradle exited with code $exit. See the log for the first error."
                    else -> verification?.exceptionOrNull()?.message ?: "No artifact was produced."
                },
            )
        }
}
