package dev.akhil.freebuff.models

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.akhil.freebuff.core.AppStorage
import dev.akhil.freebuff.core.DeviceInspector
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.core.NetworkMonitor
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ModelRecordEntity
import dev.akhil.freebuff.data.prefs.SettingsStore
import dev.akhil.freebuff.networking.HuggingFaceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Owns the download/install state machine and the WorkManager queue.
 *
 * Downloads are strictly sequential: a single unique work chain is enqueued so a
 * 2 GB phone never streams four large files at once. Work is constrained to
 * unmetered networks when the user asked for Wi-Fi-only downloads, and it keeps
 * running with the app closed (WorkManager foreground worker).
 */
class ModelInstaller(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: AppStorage,
    private val settings: SettingsStore,
    private val hf: HuggingFaceClient,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    fun observeModels(): Flow<List<ModelRecordEntity>> = db.models().observeAll()

    suspend fun get(id: String): ModelRecordEntity? = db.models().get(id)

    /**
     * Seeds the catalogue into the database and reconciles it with what is
     * actually on disk. A record is only INSTALLED when the file exists with the
     * expected byte length.
     */
    suspend fun seedCatalog(): Unit = withContext(Dispatchers.IO) {
        val existing = db.models().list().associateBy { it.id }
        val seeded = BuiltInCatalog.all.map { spec ->
            val current = existing[spec.id]
            val destination = destinationFor(spec)
            val onDisk = destination.exists() && spec.sizeBytes > 0 &&
                destination.length() == spec.sizeBytes
            if (current != null && current.status == InstallStatus.INSTALLED.name && !onDisk) {
                current.copy(
                    status = InstallStatus.NOT_INSTALLED.name,
                    installedPath = null,
                    installedAt = 0,
                    downloadedBytes = 0,
                    error = "File missing or truncated on disk; reinstall required.",
                )
            } else if (current == null) {
                spec.toRecord(if (onDisk) InstallStatus.INSTALLED else InstallStatus.NOT_INSTALLED)
                    .copy(
                        installedPath = if (onDisk) destination.absolutePath else null,
                        installedAt = if (onDisk) destination.lastModified() else 0,
                        downloadedBytes = if (onDisk) destination.length() else 0,
                    )
            } else {
                current.copy(
                    displayName = spec.displayName,
                    sizeBytes = spec.sizeBytes,
                    sha256 = spec.sha256 ?: current.sha256,
                    url = spec.url,
                    license = spec.license,
                    notes = spec.notes,
                )
            }
        }
        db.models().upsertAll(seeded)
    }

    /** Adds every not-yet-installed asset to one sequential queue. */
    suspend fun enqueueInstall(specs: List<ModelSpec> = BuiltInCatalog.installOrder): QueueResult {
        val appSettings = settings.current()
        val pending = specs.filter { spec ->
            db.models().get(spec.id)?.status != InstallStatus.INSTALLED.name
        }
        if (pending.isEmpty()) return QueueResult(queued = 0, skipped = specs.size, reason = "Everything is installed.")

        val needed = pending.sumOf { it.sizeBytes + it.companionFiles.sumOf { c -> c.sizeBytes } }
        if (!DeviceInspector.hasSufficientStorage(context, needed)) {
            return QueueResult(
                queued = 0,
                skipped = specs.size,
                reason = "Not enough free storage: ${Format.bytes(needed)} needed " +
                    "(${Format.bytes(DeviceInspector.inspect(context).freeStorageBytes)} free).",
            )
        }
        if (!NetworkMonitor.isOnline(context)) {
            return QueueResult(queued = 0, skipped = specs.size, reason = "No network connection.")
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (appSettings.wifiOnlyDownloads && !appSettings.allowMeteredDownloads) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                },
            )
            .setRequiresStorageNotLow(true)
            .build()

        val requests = pending.map { spec ->
            db.models().updateStatus(spec.id, InstallStatus.QUEUED.name, null)
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to spec.id))
                .setConstraints(constraints)
                .addTag(TAG_INSTALL)
                .addTag(spec.id)
                .build()
        }

        var continuation = workManager.beginUniqueWork(
            DownloadWorker.UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            requests.first(),
        )
        requests.drop(1).forEach { continuation = continuation.then(it) }
        continuation.enqueue()

        return QueueResult(queued = pending.size, skipped = specs.size - pending.size, reason = null)
    }

    fun pauseDownloads() {
        workManager.cancelUniqueWork(DownloadWorker.UNIQUE_WORK)
    }

    /** Re-enqueues everything that is not installed, resuming from partial files. */
    suspend fun resumeDownloads(): QueueResult = enqueueInstall()

    suspend fun retry(id: String): QueueResult {
        val spec = BuiltInCatalog.find(id)
        if (spec == null) {
            val record = db.models().get(id) ?: return QueueResult(0, 0, "Unknown asset.")
            db.models().updateStatus(id, InstallStatus.QUEUED.name, null)
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_MODEL_ID to record.id))
                .build()
            workManager.enqueue(request)
            return QueueResult(1, 0, null)
        }
        return enqueueInstall(listOf(spec))
    }

    suspend fun cancel(id: String) {
        workManager.cancelAllWorkByTag(id)
        db.models().updateStatus(id, InstallStatus.CANCELLED.name, "Cancelled by user.")
    }

    /** Deletes an asset from disk. Custom records are removed from the catalogue too. */
    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val record = db.models().get(id) ?: error("Unknown asset.")
            val path = record.installedPath
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists() && file.canonicalPath.startsWith(storage.root.canonicalPath)) {
                    file.delete()
                    BuiltInCatalog.find(id)?.companionFiles?.forEach { companion ->
                        File(file.parentFile, companion.fileName).takeIf { it.exists() }?.delete()
                    }
                    file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                }
            }
            db.models().updateInstallState(id, InstallStatus.NOT_INSTALLED.name, 0, null, 0, null)
            if (record.custom) db.models().deleteCustom(id)
        }
    }

    // ---------------------------------------------------------------------
    // Custom models
    // ---------------------------------------------------------------------

    /** Imports a GGUF file the user picked through the Storage Access Framework. */
    suspend fun importFromUri(uri: Uri, displayName: String): Result<ModelRecordEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = "custom-" + dev.akhil.freebuff.core.Ids.short()
                val safeName = Format.slug(displayName.ifBlank { "custom-model" }) + "-" + id.take(6) + ".gguf"
                val target = File(storage.modelsDir, safeName)
                val resolver = context.contentResolver
                val expectedSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (expectedSize > 0 &&
                    !DeviceInspector.hasSufficientStorage(context, expectedSize)
                ) {
                    error("Not enough free storage for this model (${Format.bytes(expectedSize)}).")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                } ?: error("Could not open the selected file.")

                val info = GgufInspector.inspect(target).getOrElse { error ->
                    target.delete()
                    throw IllegalStateException(
                        "That file is not a valid GGUF model: ${error.message}",
                    )
                }
                if (!info.architectureSupported) {
                    target.delete()
                    throw IllegalStateException(SupportedArchitectures.explanation(info.architecture))
                }

                val record = ModelRecordEntity(
                    id = id,
                    displayName = displayName.ifBlank { info.name },
                    kind = AssetKind.LLM.name,
                    repo = null,
                    fileName = target.name,
                    url = null,
                    sizeBytes = target.length(),
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    license = "Imported by you — verify the licence yourself",
                    quantization = info.quantization,
                    architecture = info.architecture,
                    role = "Custom model",
                    status = InstallStatus.INSTALLED.name,
                    downloadedBytes = target.length(),
                    installedPath = target.absolutePath,
                    installedAt = System.currentTimeMillis(),
                    custom = true,
                    notes = "GGUF v${info.version}, ${info.tensorCount} tensors, ctx ${info.contextLength}",
                )
                db.models().upsert(record)
                record
            }
        }

    /**
     * Adds a model by repository + exact file name. The file must exist upstream,
     * must be a GGUF, and its architecture must be supported before it is queued.
     */
    suspend fun addFromHuggingFace(
        repo: String,
        fileName: String,
        displayName: String?,
    ): Result<ModelRecordEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanRepo = repo.trim().trimEnd('/').removePrefix("https://huggingface.co/")
            require(cleanRepo.contains("/")) { "Enter a repository as owner/name." }
            require(fileName.endsWith(".gguf", ignoreCase = true)) { "Only .gguf files can be added." }

            val info = hf.repositoryInfo(cleanRepo).getOrElse { throw it }
            if (info.gated) error("This repository is gated. Accept its licence on huggingface.co first.")
            if (!info.files.contains(fileName)) {
                val nearest = info.files.filter { it.endsWith(".gguf", true) }.take(8).joinToString(", ")
                error("File '$fileName' was not found in $cleanRepo. Available: ${nearest.ifBlank { "none" }}")
            }
            val meta = hf.resolve(cleanRepo, fileName)
                .recoverCatching { hf.resolveViaApi(cleanRepo, fileName).getOrThrow() }
                .getOrElse { throw it }

            val id = "hf-" + dev.akhil.freebuff.core.Ids.short()
            val record = ModelRecordEntity(
                id = id,
                displayName = displayName?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast("."),
                kind = AssetKind.LLM.name,
                repo = cleanRepo,
                fileName = fileName,
                url = meta.url,
                sizeBytes = meta.sizeBytes,
                sha256 = meta.sha256,
                license = info.license ?: "Not declared in the model card",
                quantization = "unknown",
                architecture = "unverified",
                role = "Custom model",
                status = InstallStatus.NOT_INSTALLED.name,
                custom = true,
                notes = "Architecture is verified from the GGUF header after download.",
            )
            if (meta.sizeBytes > 0 && !DeviceInspector.hasSufficientStorage(context, meta.sizeBytes)) {
                error("Not enough free storage for ${Format.bytes(meta.sizeBytes)}.")
            }
            db.models().upsert(record)
            record
        }
    }

    private fun destinationFor(spec: ModelSpec): File = when (spec.kind) {
        AssetKind.LLM -> File(storage.modelsDir, spec.fileName)
        AssetKind.STT -> File(storage.sttDir, spec.fileName)
        AssetKind.TTS -> File(File(storage.ttsDir, spec.id), spec.fileName)
        AssetKind.TOKENIZER -> File(storage.modelsDir, spec.fileName)
    }

    private fun ModelSpec.toRecord(status: InstallStatus) = ModelRecordEntity(
        id = id,
        displayName = displayName,
        kind = kind.name,
        repo = repo,
        fileName = fileName,
        url = url,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        license = license,
        quantization = quantization,
        architecture = architecture,
        role = role,
        status = status.name,
        custom = false,
        notes = notes,
    )

    data class QueueResult(val queued: Int, val skipped: Int, val reason: String?)

    companion object {
        const val TAG_INSTALL = "model-install"
    }
}
