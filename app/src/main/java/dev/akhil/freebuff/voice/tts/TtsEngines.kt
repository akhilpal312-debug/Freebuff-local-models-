package dev.akhil.freebuff.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.coroutines.resume

/** Speech output abstraction. Two real implementations, no placeholder audio. */
interface TtsEngine {
    val label: String
    val isLocal: Boolean

    suspend fun prepare(): Result<Unit>
    suspend fun speak(text: String, language: String): Result<Unit>
    fun stop()
    fun release()
}

/**
 * The platform speech engine. It is on-device and works offline once the device
 * has the language installed; Hindi availability varies by manufacturer, so the
 * result is checked and reported rather than assumed.
 */
class SystemTts(private val context: Context) : TtsEngine {

    override val label: String = "Android speech engine"
    override val isLocal: Boolean = true

    private var tts: TextToSpeech? = null
    private var ready = false
    private var currentUtterance: String? = null
    private var rate: Float = 1.0f

    fun setRate(value: Float) {
        rate = value.coerceIn(0.5f, 1.8f)
        tts?.setSpeechRate(rate)
    }

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.Main) {
        if (ready) return@withContext Result.success(Unit)
        suspendCancellableCoroutine { continuation ->
            val engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setSpeechRate(rate)
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    if (continuation.isActive) continuation.resume(Result.success(Unit))
                } else {
                    if (continuation.isActive) {
                        continuation.resume(
                            Result.failure(IllegalStateException("The device has no working text-to-speech engine.")),
                        )
                    }
                }
            }
            tts = engine
        }
    }

    override suspend fun speak(text: String, language: String): Result<Unit> =
        withContext(Dispatchers.Main) {
            val engine = tts ?: return@withContext Result.failure(
                IllegalStateException("Speech engine is not ready."),
            )
            if (!ready) {
                return@withContext Result.failure(IllegalStateException("Speech engine is not ready."))
            }
            val locale = when {
                language.startsWith("hi") -> Locale("hi", "IN")
                language.startsWith("en") -> Locale.US
                else -> Locale.getDefault()
            }
            val availability = engine.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "This device has no offline ${locale.displayLanguage} voice installed. " +
                            "Install it in Android Settings → System → Languages & input → Text-to-speech.",
                    ),
                )
            }
            engine.language = locale
            suspendCancellableCoroutine { continuation ->
                val id = "freebuff-" + System.nanoTime()
                currentUtterance = id
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Result.success(Unit))
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) {
                            continuation.resume(Result.failure(IllegalStateException("Speech playback failed.")))
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) {
                            continuation.resume(
                                Result.failure(IllegalStateException("Speech playback failed (code $errorCode).")),
                            )
                        }
                    }
                })
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (result != TextToSpeech.SUCCESS) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException("The speech engine rejected the text.")))
                    }
                }
            }
        }

    override fun stop() {
        runCatching { tts?.stop() }
        currentUtterance = null
    }

    override fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}

/**
 * Piper neural voices on ONNX Runtime.
 *
 * Real synthesis: phonemes are produced from the text, mapped through the
 * voice's own `phoneme_id_map`, run through the ONNX graph and played as PCM.
 * Hindi uses [HindiPhonemizer]; for English the platform voice is used instead,
 * because a rule-based English G2P would be guesswork and Piper's English voices
 * are trained on eSpeak phonemes.
 */
class PiperTts(private val context: Context) : TtsEngine {

    override val label: String = "Piper (neural, on-device)"
    override val isLocal: Boolean = true

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var phonemeIds: Map<String, List<Long>> = emptyMap()
    private var sampleRate: Int = 22_050
    private var noiseScale: Float = 0.667f
    private var lengthScale: Float = 1.0f
    private var noiseW: Float = 0.8f
    private var loadedVoice: String? = null
    private var track: AudioTrack? = null
    @Volatile private var stopped = false

    val isLoaded: Boolean get() = session != null
    val voiceName: String? get() = loadedVoice

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(session != null) { "No Piper voice is loaded." }
        }
    }

    /** Loads `<voice>.onnx` plus its mandatory `<voice>.onnx.json` config. */
    suspend fun load(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val model = File(modelPath)
            require(model.exists()) { "Voice model not found: ${model.name}" }
            val configFile = File(model.parentFile, model.name + ".json")
            require(configFile.exists()) {
                "The voice config ${model.name}.json is missing. Re-download the voice."
            }
            val config = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(configFile.readText()).jsonObject

            val map = mutableMapOf<String, List<Long>>()
            config["phoneme_id_map"]?.jsonObject?.forEach { (symbol, value) ->
                val ids = value.jsonArray.mapNotNull { it.jsonPrimitive.content.toLongOrNull() }
                if (ids.isNotEmpty()) map[symbol] = ids
            }
            require(map.isNotEmpty()) { "The voice config has no phoneme_id_map." }

            val audio = config["audio"]?.jsonObject
            sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.content?.toIntOrNull() ?: 22_050
            val inference = config["inference"]?.jsonObject
            noiseScale = inference?.get("noise_scale")?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.667f
            lengthScale = inference?.get("length_scale")?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
            noiseW = inference?.get("noise_w")?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.8f

            releaseSession()
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = environment.createSession(model.absolutePath, options)
            env = environment
            phonemeIds = map
            loadedVoice = model.nameWithoutExtension
        }
    }

    override suspend fun speak(text: String, language: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val currentSession = session ?: error("No Piper voice is loaded.")
            val environment = env ?: error("ONNX runtime is not initialised.")
            stopped = false
            val chunks = chunk(text)
            for (chunkText in chunks) {
                if (stopped) break
                val phonemes = if (HindiPhonemizer.isDevanagari(chunkText)) {
                    HindiPhonemizer.phonemize(chunkText)
                } else {
                    // Latin text through a Hindi voice would be mispronounced; the
                    // router only sends Devanagari here, so refuse rather than guess.
                    throw IllegalStateException(
                        "This Piper voice speaks Devanagari text. English replies use the platform voice.",
                    )
                }
                if (phonemes.isBlank()) continue
                val ids = toIds(phonemes)
                if (ids.isEmpty()) continue
                val pcm = infer(environment, currentSession, ids)
                if (pcm.isNotEmpty()) playPcm(pcm)
            }
        }
    }

    private fun toIds(phonemes: String): LongArray {
        val ids = mutableListOf<Long>()
        var index = 0
        while (index < phonemes.length) {
            val match = phonemeIds.keys
                .filter { phonemes.startsWith(it, index) }
                .maxByOrNull { it.length }
            if (match != null) {
                ids += phonemeIds.getValue(match)
                index += match.length
            } else {
                // Unknown symbol: skip it rather than emit a wrong phoneme.
                index++
            }
        }
        return ids.toLongArray()
    }

    private fun infer(environment: OrtEnvironment, session: OrtSession, ids: LongArray): ShortArray {
        val inputTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(ids),
            longArrayOf(1, ids.size.toLong()),
        )
        val lengthTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(ids.size.toLong())),
            longArrayOf(1),
        )
        val scaleTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(floatArrayOf(noiseScale, lengthScale, noiseW)),
            longArrayOf(3),
        )
        inputTensor.use { input ->
            lengthTensor.use { lengths ->
                scaleTensor.use { scales ->
                    session.run(
                        mapOf("input" to input, "input_lengths" to lengths, "scales" to scales),
                    ).use { result ->
                        val raw = result[0].value
                        @Suppress("UNCHECKED_CAST")
                        val audio = (raw as Array<Array<FloatArray>>)[0][0]
                        return FloatArray(audio.size) { audio[it] }.toPcm16()
                    }
                }
            }
        }
    }

    private fun playPcm(pcm: ShortArray) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, pcm.size * 2)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
        track = audioTrack
        try {
            audioTrack.play()
            var offset = 0
            while (offset < pcm.size && !stopped) {
                val written = audioTrack.write(pcm, offset, minOf(2048, pcm.size - offset))
                if (written <= 0) break
                offset += written
            }
            if (!stopped) {
                runCatching { audioTrack.stop() }
                runCatching { audioTrack.flush() }
            }
        } finally {
            runCatching { audioTrack.release() }
            track = null
        }
    }

    private fun FloatArray.toPcm16(): ShortArray = ShortArray(size) { index ->
        val clamped = this[index].coerceIn(-1f, 1f)
        (clamped * 32767f).toInt().toShort()
    }

    private fun chunk(text: String, maxChars: Int = 260): List<String> {
        val sentences = text.split(Regex("(?<=[.!?।])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        sentences.forEach { sentence ->
            if (current.length + sentence.length > maxChars && current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
            }
            if (sentence.length > maxChars) {
                sentence.chunked(maxChars).forEach { chunks += it }
            } else {
                current.append(sentence).append(' ')
            }
        }
        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks.filter { it.isNotBlank() }
    }

    override fun stop() {
        stopped = true
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    private fun releaseSession() {
        runCatching { session?.close() }
        session = null
        loadedVoice = null
        phonemeIds = emptyMap()
    }

    override fun release() {
        stop()
        releaseSession()
    }
}

/** Chooses a voice for each reply, keeping the microphone path half-duplex. */
class TtsRouter(
    private val system: SystemTts,
    private val piper: PiperTts,
) {
    private var piperReady = false

    val backendLabel: String
        get() = if (piperReady && piper.isLoaded) "Piper neural voice (${piper.voiceName})" else system.label

    fun isUsingPiper(): Boolean = piperReady && piper.isLoaded

    suspend fun warmUp(piperModelPath: String?): String {
        val systemResult = system.prepare()
        piperReady = false
        if (piperModelPath != null && File(piperModelPath).exists()) {
            val loaded = piper.load(piperModelPath)
            piperReady = loaded.isSuccess
        }
        return when {
            piperReady -> backendLabel
            systemResult.isSuccess -> system.label
            else -> "No speech output available"
        }
    }

    fun setRate(rate: Float) = system.setRate(rate)

    /**
     * Speaks [text]. Hindi Devanagari goes to Piper when a Hindi voice is loaded;
     * everything else uses the platform engine, and a failure from either is
     * reported instead of silently doing nothing.
     */
    suspend fun speak(text: String, preferredLanguage: String): Result<String> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Nothing to speak."))
        val devanagari = HindiPhonemizer.isDevanagari(text)
        if (devanagari && piperReady) {
            val attempt = piper.speak(text, "hi")
            if (attempt.isSuccess) return Result.success("piper")
        }
        val language = when {
            devanagari -> "hi"
            preferredLanguage.startsWith("hi") && !text.any { it.code < 128 } -> "hi"
            else -> "en"
        }
        return system.speak(text, language).map { "system" }
    }

    fun stop() {
        piper.stop()
        system.stop()
    }

    fun release() {
        piper.release()
        system.release()
    }
}
