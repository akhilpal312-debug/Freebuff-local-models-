package dev.akhil.freebuff.models

/**
 * Built-in asset catalogue.
 *
 * Every repository, file name, byte size and SHA-256 below was resolved from the
 * live Hugging Face hub when this catalogue was written. The app re-resolves the
 * URL/size/hash at download time and refuses to install a file whose digest does
 * not match, so a silently substituted model can never be marked installed.
 */
object BuiltInCatalog {

    private const val HF = "https://huggingface.co"

    private fun ggufUrl(repo: String, file: String) = "$HF/$repo/resolve/main/$file"

    val gemma = ModelSpec(
        id = "gemma-3-270m-it",
        displayName = "Gemma 3 270M IT",
        kind = AssetKind.LLM,
        repo = "gguf-org/gemma-3-270m-it-gguf",
        fileName = "gemma-3-270m-it-q4_k_m.gguf",
        url = ggufUrl("gguf-org/gemma-3-270m-it-gguf", "gemma-3-270m-it-q4_k_m.gguf"),
        sizeBytes = 253_114_880L,
        sha256 = "55aed1673d2f3014a739732c659c4995fc57abf6e4e9c68462fb2ceee820ea2f",
        license = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        quantization = "Q4_K_M",
        architecture = "gemma3",
        role = "Lightweight general conversation",
        minRamMb = 700,
        required = true,
        notes = "Smallest of the three LLMs. Google's Gemma licence applies to outputs as well.",
    )

    val qwenCoder = ModelSpec(
        id = "qwen2.5-coder-0.5b",
        displayName = "Qwen2.5-Coder 0.5B Instruct",
        kind = AssetKind.LLM,
        repo = "Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
        fileName = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
        url = ggufUrl("Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF", "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf"),
        sizeBytes = 491_400_064L,
        sha256 = "1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
        quantization = "Q4_K_M",
        architecture = "qwen2",
        role = "Coding and code explanation",
        minRamMb = 1100,
        required = true,
        notes = "Better on code than the general models but still very small; expect short, focused edits.",
    )

    val qwen3 = ModelSpec(
        id = "qwen3-0.6b",
        displayName = "Qwen3 0.6B",
        kind = AssetKind.LLM,
        repo = "ggml-org/Qwen3-0.6B-GGUF",
        fileName = "Qwen3-0.6B-Q4_0.gguf",
        url = ggufUrl("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf"),
        sizeBytes = 428_970_080L,
        sha256 = "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4",
        license = "Apache-2.0",
        licenseUrl = "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF",
        quantization = "Q4_0",
        architecture = "qwen3",
        role = "Reasoning and planning experiments",
        minRamMb = 1000,
        required = true,
        notes = "Emits thinking blocks by default; the app keeps them out of the final answer.",
    )

    val whisperTiny = ModelSpec(
        id = "whisper-tiny",
        displayName = "Whisper Tiny (multilingual)",
        kind = AssetKind.STT,
        repo = "ggerganov/whisper.cpp",
        fileName = "ggml-tiny.bin",
        url = ggufUrl("ggerganov/whisper.cpp", "ggml-tiny.bin"),
        sizeBytes = 77_691_713L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
        license = "MIT (whisper.cpp runtime, OpenAI Whisper weights)",
        licenseUrl = "https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE",
        quantization = "F16",
        architecture = "whisper",
        role = "Hindi, Hinglish and English speech recognition",
        minRamMb = 300,
        required = true,
        notes = "Tiny multilingual is the only size that fits 2 GB phones; expect errors on Hinglish.",
    )

    val piperHindi = ModelSpec(
        id = "piper-hi-pratham",
        displayName = "Piper Hindi — Pratham (medium)",
        kind = AssetKind.TTS,
        repo = "rhasspy/piper-voices",
        fileName = "hi_IN-pratham-medium.onnx",
        url = ggufUrl("rhasspy/piper-voices", "hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx"),
        sizeBytes = 63_516_050L,
        sha256 = null,
        license = "See voice card in rhasspy/piper-voices (voices may carry their own licence)",
        licenseUrl = "https://huggingface.co/rhasspy/piper-voices",
        quantization = "medium",
        architecture = "piper",
        role = "Hindi speech output",
        minRamMb = 220,
        required = true,
        companionFiles = listOf(
            CompanionFile(
                fileName = "hi_IN-pratham-medium.onnx.json",
                url = "$HF/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json",
                sizeBytes = 4_970L,
            ),
        ),
        notes = "Needs the matching .onnx.json config next to the model file.",
    )

    val piperEnglish = ModelSpec(
        id = "piper-en-lessac",
        displayName = "Piper English — Lessac (medium)",
        kind = AssetKind.TTS,
        repo = "rhasspy/piper-voices",
        fileName = "en_US-lessac-medium.onnx",
        url = ggufUrl("rhasspy/piper-voices", "en/en_US/lessac/medium/en_US-lessac-medium.onnx"),
        sizeBytes = 63_201_294L,
        sha256 = null,
        license = "See voice card in rhasspy/piper-voices (voices may carry their own licence)",
        licenseUrl = "https://huggingface.co/rhasspy/piper-voices",
        quantization = "medium",
        architecture = "piper",
        role = "English speech output",
        minRamMb = 220,
        required = true,
        companionFiles = listOf(
            CompanionFile(
                fileName = "en_US-lessac-medium.onnx.json",
                url = "$HF/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
                sizeBytes = 4_885L,
            ),
        ),
        notes = "Needs the matching .onnx.json config next to the model file.",
    )

    /** Installation order. LLMs first, then speech, sequentially — never in parallel. */
    val installOrder: List<ModelSpec> = listOf(
        gemma,
        qwenCoder,
        qwen3,
        whisperTiny,
        piperHindi,
        piperEnglish,
    )

    val llms: List<ModelSpec> = listOf(gemma, qwenCoder, qwen3)

    val all: List<ModelSpec> = installOrder

    val totalDownloadBytes: Long = installOrder.sumOf { spec ->
        spec.sizeBytes + spec.companionFiles.sumOf { it.sizeBytes }
    }

    fun find(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    fun defaultLlm(): ModelSpec = gemma

    /**
     * Tokenizer/vocabulary assets are embedded inside GGUF files, and Piper
     * voices ship their phoneme config as the .onnx.json companion. There is no
     * separate tokenizer download to perform, and the app does not need one.
     */
    const val TOKENIZER_NOTE =
        "GGUF files embed their tokenizer; Piper voices ship their config as a sibling .json file. " +
            "No separate tokenizer asset is downloaded."
}
