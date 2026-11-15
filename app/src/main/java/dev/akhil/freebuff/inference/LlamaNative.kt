package dev.akhil.freebuff.inference

/**
 * JNI surface for the bundled llama.cpp build (see app/src/main/cpp/).
 *
 * The library is only present in APKs built with `-PwithNative=true`. When it is
 * missing, [LibraryProbe.available] stays false and the UI reports that local
 * inference is not part of this build — it never pretends a model replied.
 */
object LlamaNative {

    const val LIBRARY_NAME = "freebuff_llama"

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    init {
        attemptLoad()
    }

    private fun attemptLoad() {
        available = runCatching {
            System.loadLibrary(LIBRARY_NAME)
            true
        }.getOrElse { error ->
            loadError = error.message ?: error.javaClass.simpleName
            false
        }
    }

    /** Retries loading the shared library after a failed attempt. */
    fun retryLoad(): Boolean {
        if (available) return true
        attemptLoad()
        return available
    }

    @JvmStatic external fun nativeBackendInfo(): String

    /** Returns a native handle, or 0 on failure (reason via [nativeLastError]). */
    @JvmStatic external fun nativeLoadModel(
        path: String,
        contextTokens: Int,
        threads: Int,
        useMmap: Boolean,
    ): Long

    @JvmStatic external fun nativeFreeModel(handle: Long)

    @JvmStatic external fun nativeLastError(): String

    /** Blocks until generation finishes; tokens are pushed to [callback]. Returns generated token count, -1 on error. */
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int,
        callback: TokenSink,
    ): Int

    @JvmStatic external fun nativeStop(handle: Long)

    @JvmStatic external fun nativeModelDescription(handle: Long): String
}

/** Native code calls back into this from the generation thread. */
interface TokenSink {
    /** @return true to keep generating, false to stop. */
    fun onToken(text: String): Boolean
}
