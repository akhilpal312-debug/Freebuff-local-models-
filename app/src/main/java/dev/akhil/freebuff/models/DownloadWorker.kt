package dev.akhil.freebuff.models

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.akhil.freebuff.core.AppContainer
import dev.akhil.freebuff.core.NetworkMonitor
import dev.akhil.freebuff.core.Notifications
import dev.akhil.freebuff.data.db.ModelRecordEntity
import dev.akhil.freebuff.networking.HuggingFaceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Downloads exactly one asset (plus its companion files) and installs it
 * atomically. Chained sequentially by [ModelInstaller]; never runs several large
 * transfers at once on a low-memory phone.
 *
 * Guarantees:
 *  - resumes from the last byte using HTTP Range;
 *  - verifies length and, when known, SHA-256 before an atomic rename;
 *  - never marks a partial or corrupt file as installed;
 *  - keeps the partial file on cancellation so a later run can resume.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val container by lazy { AppContainer.of(applicationContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure(
            workDataOf(KEY_ERROR to "No model id supplied."),
        )
        val record = container.db.models().get(modelId)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Unknown model $modelId."))

        if (record.status == InstallStatus.INSTALLED.name) {
            val path = record.installedPath
            if (path != null && File(path).exists()) return@withContext Result.success()
        }

        val url = record.url
        if (url.isNullOrBlank()) {
            container.db.models().updateStatus(modelId, InstallStatus.FAILED.name, "No download URL for this asset.")
            return@withContext Result.failure()
        }

        val settings = container.settings.current()
        if (!NetworkMonitor.isOnline(applicationContext)) {
            return@withContext retryOrFail(modelId, "No network connection.", attemptsLeft = 3)
        }
        if (NetworkMonitor.isMetered(applicationContext) && !settings.allowMeteredDownloads) {
            container.db.models().updateStatus(
                modelId,
                InstallStatus.QUEUED.name,
                "Waiting for Wi-Fi (mobile data disabled in Settings).",
            )
            return@withContext Result.retry()
        }

        val requiredBytes = (record.sizeBytes - record.downloadedBytes).coerceAtLeast(0)
        if (!dev.akhil.freebuff.core.DeviceInspector.hasSufficientStorage(applicationContext, requiredBytes)) {
            val message = "Not enough free storage. Free up " +
                dev.akhil.freebuff.core.Format.bytes(requiredBytes + 400L * 1024 * 1024) + " and retry."
            container.db.models().updateStatus(modelId, InstallStatus.FAILED.name, message)
            return@withContext Result.failure(workDataOf(KEY_ERROR to message))
        }

        container.db.models().updateStatus(modelId, InstallStatus.DOWNLOADING.name, null)
        setForegroundSafely(buildNotification(record.displayName, "Starting…", 0, indeterminate = true))

        val destination = destinationFor(record)
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.parentFile?.mkdirs()

        var lastReport = 0L
        var downloaded = if (partial.exists()) partial.length() else 0L

        try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    // Range not satisfiable: the partial is already complete or unusable.
                    if (partial.length() == record.sizeBytes) {
                        return@use
                    }
                    partial.delete()
                    downloaded = 0
                    throw java.io.IOException("Server rejected resume range; restarting download.")
                }
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw java.io.IOException("Empty response body.")
                val append = response.code == 206 && downloaded > 0
                if (response.code == 200 && downloaded > 0) {
                    // Server ignored the Range header: start over rather than corrupt the file.
                    downloaded = 0
                }
                val total = if (append) record.sizeBytes else body.contentLength().takeIf { it > 0 } ?: record.sizeBytes

                RandomAccessFile(partial, "rw").use { out ->
                    out.seek(if (append) downloaded else 0L)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) throw kotlinx.coroutines.CancellationException("Download cancelled")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 700) {
                                lastReport = now
                                val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                                container.db.models().updateInstallState(
                                    modelId,
                                    InstallStatus.DOWNLOADING.name,
                                    downloaded,
                                    null,
                                    0,
                                    null,
                                )
                                setForegroundSafely(
                                    buildNotification(
                                        record.displayName,
                                        "${dev.akhil.freebuff.core.Format.bytes(downloaded)} of " +
                                            dev.akhil.freebuff.core.Format.bytes(total),
                                        progress,
                                        indeterminate = total <= 0,
                                    ),
                                )
                            }
                        }
                        out.fd.sync()
                    }
                }
            }

            // ---- verification -------------------------------------------------
            container.db.models().updateStatus(modelId, InstallStatus.VERIFYING.name, null)
            setForegroundSafely(
                buildNotification(record.displayName, "Verifying file…", 100, indeterminate = true),
            )

            if (record.sizeBytes > 0 && partial.length() != record.sizeBytes) {
                throw java.io.IOException(
                    "Size mismatch: expected ${record.sizeBytes} bytes, got ${partial.length()}.",
                )
            }

            val expectedSha = record.sha256
            if (!expectedSha.isNullOrBlank()) {
                val actual = sha256(partial)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    partial.delete()
                    container.db.models().updateStatus(
                        modelId,
                        InstallStatus.FAILED.name,
                        "Checksum mismatch — the downloaded file was discarded.",
                    )
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "SHA-256 mismatch"))
                }
            }

            // GGUF files must actually be loadable models, not arbitrary bytes.
            if (record.kind == AssetKind.LLM.name) {
                val info = GgufInspector.inspect(partial).getOrElse { error ->
                    partial.delete()
                    container.db.models().updateStatus(
                        modelId,
                        InstallStatus.FAILED.name,
                        "Downloaded file is not a valid GGUF model: ${error.message}",
                    )
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "Invalid GGUF"))
                }
                if (!info.architectureSupported) {
                    partial.delete()
                    container.db.models().updateStatus(
                        modelId,
                        InstallStatus.INCOMPATIBLE.name,
                        SupportedArchitectures.explanation(info.architecture),
                    )
                    return@withContext Result.failure(workDataOf(KEY_ERROR to "Unsupported architecture"))
                }
            }

            if (destination.exists()) destination.delete()
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }

            // Companion files (e.g. the Piper .onnx.json voice config).
            val spec = BuiltInCatalog.find(record.id)
            spec?.companionFiles?.forEach { companion ->
                downloadCompanion(client, companion, File(destination.parentFile, companion.fileName))
            }

            container.db.models().updateInstallState(
                modelId,
                InstallStatus.INSTALLED.name,
                destination.length(),
                destination.absolutePath,
                System.currentTimeMillis(),
                null,
            )
            Notifications.downloadComplete(
                applicationContext,
                "Installed",
                "${record.displayName} is ready to use offline.",
            )
            Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Keep the partial file: a resume picks up where this stopped.
            container.db.models().updateStatus(
                modelId,
                InstallStatus.CANCELLED.name,
                "Download cancelled. Progress kept for resume.",
            )
            throw cancelled
        } catch (error: Exception) {
            retryOrFail(modelId, error.message ?: error.javaClass.simpleName, attemptsLeft = 3 - runAttemptCount)
        }
    }

    private suspend fun downloadCompanion(client: OkHttpClient, companion: CompanionFile, target: File) {
        if (target.exists() && companion.sizeBytes > 0 && target.length() == companion.sizeBytes) return
        val request = Request.Builder().url(companion.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Companion HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty companion body.")
            val temp = File(target.parentFile, "${target.name}.part")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (companion.sizeBytes > 0 && temp.length() != companion.sizeBytes) {
                temp.delete()
                throw java.io.IOException("Companion size mismatch for ${companion.fileName}")
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }

    private suspend fun retryOrFail(modelId: String, message: String, attemptsLeft: Int): Result {
        return if (attemptsLeft > 1) {
            container.db.models().updateStatus(modelId, InstallStatus.QUEUED.name, message)
            Result.retry()
        } else {
            container.db.models().updateStatus(modelId, InstallStatus.FAILED.name, message)
            Result.failure(workDataOf(KEY_ERROR to message))
        }
    }

    private fun destinationFor(record: ModelRecordEntity): File {
        val storage = container.storage
        return when (record.kind) {
            AssetKind.LLM.name -> File(storage.modelsDir, record.fileName)
            AssetKind.STT.name -> File(storage.sttDir, record.fileName)
            AssetKind.TTS.name -> File(File(storage.ttsDir, record.id), record.fileName)
            else -> File(storage.modelsDir, record.fileName)
        }
    }

    private fun buildNotification(title: String, detail: String, progress: Int, indeterminate: Boolean): ForegroundInfo {
        val notification = Notifications.downloadProgress(
            applicationContext,
            title,
            detail,
            progress,
            indeterminate,
        )
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.ID_DOWNLOAD,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Notifications.ID_DOWNLOAD, notification)
        }
    }

    private suspend fun setForegroundSafely(info: ForegroundInfo) {
        runCatching { setForeground(info) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val UNIQUE_WORK = "freebuff-model-downloads"
    }
}
