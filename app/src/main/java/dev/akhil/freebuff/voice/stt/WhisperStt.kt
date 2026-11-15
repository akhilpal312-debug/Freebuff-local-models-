package dev.akhil.freebuff.voice.stt

import dev.akhil.freebuff.voice.CaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * whisper.cpp speech recognition through JNI.
 *
 * Same honesty rule as the LLM engine: when the native library is missing in a
 * build, transcription reports that it is unavailable instead of returning
 * placeholder text.
 */
object WhisperNative {
    private const val LIBRARY = "freebuff_whisper"

    @Volatile var available: Boolean = false
        private set
    @Volatile var loadError: String? = null
        private set

    init {
        available = runCatching {
            System.loadLibrary(LIBRARY)
            true
        }.getOrElse { error ->
            loadError = error.message ?: error.javaClass.simpleName
            false
        }
    }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int): String
    @JvmStatic external fun nativeFree(handle: Long)
    @JvmStatic external fun nativeLastError(): String
}

class WhisperStt {

    @Volatile private var handle: Long = 0
    @Volatile private var loadedPath: String? = null

    val isAvailable: Boolean get() = WhisperNative.available
    val isLoaded: Boolean get() = handle != 0L
    val loadedModelName: String? get() = loadedPath?.let { File(it).name }

    fun backendLabel(): String = when {
        !WhisperNative.available -> "Not available in this build"
        isLoaded -> "whisper.cpp · ${loadedModelName}"
        else -> "whisper.cpp · model not loaded"
    }

    suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(WhisperNative.available) {
                "Speech recognition is not included in this build (${WhisperNative.loadError ?: "native library missing"})."
            }
            val file = File(modelPath)
            require(file.exists()) { "Whisper model not found: ${file.name}" }
            if (handle != 0L) unload()
            val native = WhisperNative.nativeInit(file.absolutePath)
            require(native != 0L) {
                runCatching { WhisperNative.nativeLastError() }.getOrNull()
                    ?.ifBlank { "whisper.cpp could not load ${file.name}" }
                    ?: "whisper.cpp could not load ${file.name}"
            }
            handle = native
            loadedPath = file.absolutePath
        }
    }

    suspend fun transcribe(capture: CaptureResult, language: String = "auto"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(handle != 0L) { "The speech model is not loaded." }
                require(capture.samples.isNotEmpty()) { "The recording was empty." }
                val floats = FloatArray(capture.samples.size) { capture.samples[it] / 32768f }
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
                val languageCode = when (language.lowercase()) {
                    "hi", "hindi" -> "hi"
                    "en", "english" -> "en"
                    else -> "auto"
                }
                val text = WhisperNative.nativeTranscribe(handle, floats, languageCode, threads)
                text.trim()
            }
        }

    suspend fun unload() = withContext(Dispatchers.IO) {
        val current = handle
        handle = 0
        loadedPath = null
        if (current != 0L) runCatching { WhisperNative.nativeFree(current) }
    }
}
