package dev.akhil.freebuff.core

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** Device capability probes used for honest scaling and warnings. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val cpuCores: Int,
    val supportsArm64: Boolean,
    val sdkInt: Int,
) {
    val totalRamMb: Long get() = totalRamBytes / (1024 * 1024)
    val ramTier: RamTier
        get() = when {
            totalRamMb >= 7000 -> RamTier.HIGH
            totalRamMb >= 5000 -> RamTier.MEDIUM
            totalRamMb >= 3000 -> RamTier.LOW
            else -> RamTier.MINIMAL
        }

    /** Largest model file this device can realistically keep resident. */
    val recommendedMaxModelBytes: Long
        get() = when (ramTier) {
            RamTier.HIGH -> 3_000L * 1024 * 1024
            RamTier.MEDIUM -> 1_600L * 1024 * 1024
            RamTier.LOW -> 900L * 1024 * 1024
            RamTier.MINIMAL -> 600L * 1024 * 1024
        }

    fun describe(): String =
        "${totalRamMb} MB RAM · ${cpuCores} cores · ${Format.bytes(freeStorageBytes)} free"
}

enum class RamTier { MINIMAL, LOW, MEDIUM, HIGH }

object DeviceInspector {

    fun inspect(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val stat = StatFs(context.filesDir.absolutePath)
        val abis = Build.SUPPORTED_ABIS.toList()
        return DeviceProfile(
            totalRamBytes = mi.totalMem,
            availableRamBytes = mi.availMem,
            freeStorageBytes = stat.availableBytes,
            totalStorageBytes = stat.totalBytes,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            supportsArm64 = abis.any { it.contains("arm64") || it.contains("x86_64") },
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    fun hasSufficientStorage(context: Context, requiredBytes: Long): Boolean {
        val stat = StatFs(context.filesDir.absolutePath)
        // Keep a 400 MB safety margin for runtime scratch space.
        return stat.availableBytes > requiredBytes + 400L * 1024 * 1024
    }
}

/** App-private storage layout. Nothing is written to shared storage without SAF. */
class AppStorage(private val context: Context) {

    val root: File get() = File(context.filesDir, "freebuff").apply { mkdirs() }
    val modelsDir: File get() = File(root, "models").apply { mkdirs() }
    val voiceDir: File get() = File(root, "voice").apply { mkdirs() }
    val sttDir: File get() = File(voiceDir, "stt").apply { mkdirs() }
    val ttsDir: File get() = File(voiceDir, "tts").apply { mkdirs() }
    val workspaceDir: File get() = File(root, "workspace").apply { mkdirs() }
    val projectsDir: File get() = File(workspaceDir, "projects").apply { mkdirs() }
    val buildsDir: File get() = File(root, "builds").apply { mkdirs() }
    val logsDir: File get() = File(root, "logs").apply { mkdirs() }
    val downloadsDir: File get() = File(root, "downloads").apply { mkdirs() }
    val exportsDir: File get() = File(root, "exports").apply { mkdirs() }
    val tmpDir: File get() = File(context.cacheDir, "freebuff-tmp").apply { mkdirs() }

    fun modelFile(fileName: String): File = File(modelsDir, fileName)

    fun partialFile(fileName: String): File = File(downloadsDir, "$fileName.partial")

    /** Best-effort cleanup of abandoned partial downloads older than [ageMillis]. */
    fun cleanStalePartials(ageMillis: Long = 7L * 24 * 3600 * 1000) {
        val cutoff = System.currentTimeMillis() - ageMillis
        downloadsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
