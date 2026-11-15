package dev.akhil.freebuff.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash recorder.
 *
 * A phone that closes the app the instant it is tapped gives the user nothing to
 * report, and gives the developer nothing to fix. This installs a process-wide
 * handler that writes the real stack trace plus the device fingerprint to a file
 * in app-private storage before the process dies, then delegates to whatever
 * handler was installed before (so the system still logs and reports it).
 *
 * [lastCrash] is read on the next successful launch and shown in the UI, so the
 * failure becomes a message the user can copy instead of a silent exit.
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 60_000

    @Volatile
    private var installed = false

    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record("uncaught:${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes a non-fatal startup/render failure that we handled and survived. */
    fun record(stage: String, error: Throwable) {
        val context = appContext ?: return
        runCatching {
            val body = buildString {
                appendLine("Freebuff Mobile crash record")
                appendLine("stage: $stage")
                appendLine("time: ${stamp()}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("app version: ${BuildConfigVersion.describe()}")
                appendLine()
                appendLine(stackOf(error))
            }.take(MAX_CHARS)
            File(context.filesDir, FILE_NAME).writeText(body)
        }
    }

    fun lastCrash(): String? {
        val context = appContext ?: return null
        return runCatching {
            File(context.filesDir, FILE_NAME)
                .takeIf { it.exists() && it.length() > 0 }
                ?.readText()
        }.getOrNull()
    }

    fun clear() {
        val context = appContext ?: return
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun stackOf(error: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { error.printStackTrace(it) }
        return writer.toString()
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Kept separate so the object has no BuildConfig dependency at class-load time. */
    private object BuildConfigVersion {
        fun describe(): String = runCatching {
            "${dev.akhil.freebuff.BuildConfig.APP_LABEL} ${dev.akhil.freebuff.BuildConfig.VERSION_NAME}"
        }.getOrDefault("unknown")
    }
}
