package dev.akhil.freebuff.models

import java.io.File
import java.io.RandomAccessFile

/** Metadata read straight out of a GGUF header. */
data class GgufInfo(
    val version: Int,
    val tensorCount: Long,
    val architecture: String,
    val name: String,
    val quantization: String,
    val contextLength: Int,
    val vocabularySize: Int,
) {
    val architectureSupported: Boolean get() = SupportedArchitectures.isSupported(architecture)
}

/**
 * Minimal, dependency-free GGUF metadata reader.
 *
 * Used to validate an imported or downloaded model before it is offered for
 * loading: a file is only marked usable when its architecture is one llama.cpp
 * can execute. Malformed files fail here instead of crashing the engine.
 */
object GgufInspector {

    private const val MAGIC: Long = 0x46554747L // "GGUF" little endian
    private const val MAX_STRING_BYTES = 1 shl 20
    private const val MAX_ARRAY_ITEMS = 1 shl 22

    fun inspect(file: File): Result<GgufInfo> = runCatching {
        require(file.exists()) { "File does not exist: ${file.name}" }
        require(file.length() > 16) { "File is too small to be a GGUF model." }
        RandomAccessFile(file, "r").use { raf ->
            val magic = readUInt32(raf)
            require(magic == MAGIC) { "Not a GGUF file (bad magic number)." }
            val version = readUInt32(raf).toInt()
            require(version in 1..3) { "Unsupported GGUF version $version." }
            val tensorCount = readUInt64(raf)
            val kvCount = readUInt64(raf)
            require(kvCount in 0..1_000_000) { "Implausible metadata count $kvCount." }

            var architecture = ""
            var name = ""
            var fileType = -1
            var contextLength = 0
            var vocabulary = 0

            var index = 0L
            while (index < kvCount) {
                val key = readString(raf)
                val type = readUInt32(raf).toInt()
                when {
                    key == "general.architecture" -> architecture = readStringValue(raf, type)
                    key == "general.name" -> name = readStringValue(raf, type)
                    key == "general.file_type" -> fileType = readIntValue(raf, type)
                    key.endsWith(".context_length") -> contextLength = readIntValue(raf, type)
                    key.endsWith(".vocab_size") || key == "tokenizer.ggml.tokens.size" ->
                        vocabulary = readIntValue(raf, type)
                    else -> skipValue(raf, type, 0)
                }
                index++
            }

            require(architecture.isNotBlank()) {
                "Model metadata has no general.architecture entry; cannot verify compatibility."
            }

            GgufInfo(
                version = version,
                tensorCount = tensorCount,
                architecture = architecture.lowercase(),
                name = name.ifBlank { file.nameWithoutExtension },
                quantization = quantName(fileType),
                contextLength = contextLength,
                vocabularySize = vocabulary,
            )
        }
    }

    fun quantName(fileType: Int): String = when (fileType) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "IQ3_XXS"
        22 -> "IQ1_S"
        23 -> "IQ4_NL"
        24 -> "IQ3_S"
        25 -> "IQ2_S"
        26 -> "IQ4_XS"
        27 -> "I8"
        28 -> "I16"
        29 -> "I32"
        30 -> "I64"
        31 -> "F64"
        32 -> "IQ1_M"
        33 -> "BF16"
        else -> if (fileType < 0) "unknown" else "type $fileType"
    }

    // ---- primitives -------------------------------------------------------

    private fun readBytes(raf: RandomAccessFile, count: Int): ByteArray {
        val data = ByteArray(count)
        raf.readFully(data)
        return data
    }

    private fun readUInt32(raf: RandomAccessFile): Long {
        val b = readBytes(raf, 4)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun readUInt64(raf: RandomAccessFile): Long {
        val b = readBytes(raf, 8)
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((b[i].toLong() and 0xFF) shl (8 * i))
        }
        require(value >= 0) { "Corrupt 64-bit length in GGUF metadata." }
        return value
    }

    private fun readString(raf: RandomAccessFile): String {
        val length = readUInt64(raf)
        require(length in 0..MAX_STRING_BYTES.toLong()) { "Implausible metadata string length $length." }
        return String(readBytes(raf, length.toInt()), Charsets.UTF_8)
    }

    private fun readStringValue(raf: RandomAccessFile, type: Int): String = when (type) {
        TYPE_STRING -> readString(raf)
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> readBytes(raf, 1)[0].toString()
        else -> {
            skipValue(raf, type, 0)
            ""
        }
    }

    private fun readIntValue(raf: RandomAccessFile, type: Int): Int = when (type) {
        TYPE_UINT8 -> readBytes(raf, 1)[0].toInt() and 0xFF
        TYPE_INT8 -> readBytes(raf, 1)[0].toInt()
        TYPE_UINT16, TYPE_INT16 -> {
            val b = readBytes(raf, 2)
            ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8))
        }
        TYPE_UINT32, TYPE_INT32 -> readUInt32(raf).toInt()
        TYPE_UINT64, TYPE_INT64 -> readUInt64(raf).toInt()
        TYPE_FLOAT32 -> Float.fromBits(readUInt32(raf).toInt()).toInt()
        TYPE_FLOAT64 -> readUInt64(raf).toDouble().toInt()
        else -> {
            skipValue(raf, type, 0)
            0
        }
    }

    private fun skipValue(raf: RandomAccessFile, type: Int, depth: Int) {
        require(depth < 8) { "GGUF metadata nesting too deep." }
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> raf.skipBytes(1)
            TYPE_UINT16, TYPE_INT16 -> raf.skipBytes(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> raf.skipBytes(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> raf.skipBytes(8)
            TYPE_STRING -> {
                val length = readUInt64(raf)
                require(length in 0..MAX_STRING_BYTES.toLong()) { "Implausible string length." }
                raf.skipBytes(length.toInt())
            }
            TYPE_ARRAY -> {
                val itemType = readUInt32(raf).toInt()
                val count = readUInt64(raf)
                require(count in 0..MAX_ARRAY_ITEMS.toLong()) { "Implausible array length $count." }
                var i = 0L
                while (i < count) {
                    skipValue(raf, itemType, depth + 1)
                    i++
                }
            }
            else -> throw IllegalStateException("Unknown GGUF metadata type $type")
        }
    }

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12
}
