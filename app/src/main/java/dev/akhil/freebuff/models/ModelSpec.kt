package dev.akhil.freebuff.models

/** What a downloaded asset is used for. */
enum class AssetKind { LLM, STT, TTS, TOKENIZER }

/** Where a model record came from. */
enum class ModelSource { BUILT_IN, HUGGING_FACE, LOCAL_FILE }

enum class InstallStatus {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    FAILED,
    INCOMPATIBLE,
    CANCELLED,
}

/**
 * A downloadable (or imported) asset. Every field here has been checked against
 * the upstream repository; nothing is guessed at runtime.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val kind: AssetKind,
    val repo: String?,
    val fileName: String,
    val url: String?,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val licenseUrl: String?,
    val quantization: String,
    val architecture: String,
    val role: String,
    val minRamMb: Int,
    val required: Boolean,
    val companionFiles: List<CompanionFile> = emptyList(),
    val notes: String = "",
) {
    val sizeLabel: String get() = dev.akhil.freebuff.core.Format.bytes(sizeBytes)

    /** Rough resident-memory need: weights plus KV cache and runtime overhead. */
    val estimatedLoadBytes: Long get() = sizeBytes + 160L * 1024 * 1024

    fun fitsIn(availableRamMb: Long): Boolean = availableRamMb >= minRamMb
}

data class CompanionFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/** Architectures the bundled llama.cpp build can execute. */
object SupportedArchitectures {
    val llamaCpp = setOf(
        "llama", "gemma", "gemma2", "gemma3", "qwen2", "qwen2vl", "qwen2moe",
        "qwen3", "phi2", "phi3", "mistral", "llama4", "stablelm", "gptneox",
    )

    fun isSupported(architecture: String?): Boolean {
        val arch = architecture?.lowercase()?.trim() ?: return false
        return llamaCpp.any { arch == it || arch.startsWith(it) }
    }

    fun explanation(architecture: String?): String {
        val arch = architecture?.ifBlank { null } ?: "unknown"
        return "Architecture '$arch' is not supported by the bundled llama.cpp runtime. " +
            "Supported families: ${llamaCpp.sorted().joinToString(", ")}."
    }
}
