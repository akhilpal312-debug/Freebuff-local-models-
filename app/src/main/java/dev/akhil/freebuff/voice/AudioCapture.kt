package dev.akhil.freebuff.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class CaptureResult(
    val samples: ShortArray,
    val sampleRate: Int,
    val durationMs: Long,
    val peakAmplitude: Float,
    val reason: StopReason,
)

enum class StopReason { USER_STOP, SILENCE, MAX_DURATION, ERROR }

/**
 * Microphone capture for tap-to-talk only.
 *
 * The recorder is opened when the user taps the microphone and released as soon
 * as the utterance ends. Nothing captures audio in the background, and the
 * buffer is discarded once transcription finishes unless the user opted in to
 * keeping recordings.
 *
 * End of utterance = voice activity detection: speech must start above a noise
 * threshold, then [silenceMs] of continuous quiet ends the turn.
 */
class AudioCapture {

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    @Volatile private var cancelled = false
    @Volatile private var stopRequested = false

    fun cancel() {
        cancelled = true
        stopRequested = true
    }

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Records one utterance. Returns null when the microphone is unavailable or
     * the user dismissed the turn before any speech was captured.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUtterance(
        silenceMs: Long = 1400,
        maxDurationMs: Long = 30_000,
        minSpeechMs: Long = 350,
    ): CaptureResult? = withContext(Dispatchers.IO) {
        cancelled = false
        stopRequested = false
        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return@withContext null
        val bufferSize = maxOf(minBuffer * 2, sampleRate / 4)

        val recorder = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: Exception) {
            return@withContext null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return@withContext null
        }

        val samples = ArrayList<Short>(sampleRate * 8)
        val startedAt = System.currentTimeMillis()
        var lastVoiceAt = startedAt
        var speechStarted = false
        var peak = 0f
        var noiseFloor = 0.006f

        try {
            recorder.startRecording()
            val chunk = ShortArray(sampleRate / 50) // 20 ms
            loop@ while (!cancelled) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break
                samples.addAll(chunk.take(read))

                val rms = rmsOf(chunk, read)
                peak = maxOf(peak, rms)
                _amplitude.value = (rms * 12f).coerceIn(0f, 1f)
                _elapsedSeconds.value = ((System.currentTimeMillis() - startedAt) / 1000).toInt()

                val now = System.currentTimeMillis()
                val speechThreshold = maxOf(noiseFloor * 2.2f, 0.012f)
                if (rms > speechThreshold) {
                    speechStarted = true
                    lastVoiceAt = now
                } else if (!speechStarted) {
                    // Adapt to the room while the user has not started speaking.
                    noiseFloor = (noiseFloor * 0.92f) + (rms * 0.08f)
                }

                val silentFor = now - lastVoiceAt
                val tooLong = now - startedAt > maxDurationMs
                val spokeEnough = speechStarted && (lastVoiceAt - startedAt) > minSpeechMs

                when {
                    stopRequested -> break@loop
                    tooLong -> {
                        recorder.stop()
                        return@withContext finalize(samples, sampleRate, startedAt, peak, StopReason.MAX_DURATION)
                    }
                    spokeEnough && silentFor > silenceMs -> {
                        recorder.stop()
                        return@withContext finalize(samples, sampleRate, startedAt, peak, StopReason.SILENCE)
                    }
                }
            }
            runCatching { recorder.stop() }
            if (cancelled) return@withContext null
            if (!speechStarted) return@withContext null
            finalize(samples, sampleRate, startedAt, peak, StopReason.USER_STOP)
        } catch (error: Exception) {
            null
        } finally {
            runCatching { recorder.release() }
            _amplitude.value = 0f
            _elapsedSeconds.value = 0
        }
    }

    private fun finalize(
        samples: List<Short>,
        sampleRate: Int,
        startedAt: Long,
        peak: Float,
        reason: StopReason,
    ): CaptureResult {
        _amplitude.value = 0f
        _elapsedSeconds.value = 0
        return CaptureResult(
            samples = samples.toShortArray(),
            sampleRate = sampleRate,
            durationMs = System.currentTimeMillis() - startedAt,
            peakAmplitude = peak,
            reason = reason,
        )
    }

    private fun rmsOf(buffer: ShortArray, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in 0 until length) {
            val value = buffer[i] / 32768.0
            sum += value * value
        }
        return sqrt(sum / length).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
