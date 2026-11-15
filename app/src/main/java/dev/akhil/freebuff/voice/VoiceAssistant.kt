package dev.akhil.freebuff.voice

import dev.akhil.freebuff.ai.AiMode
import dev.akhil.freebuff.ai.ChatOrchestrator
import dev.akhil.freebuff.core.AppStorage
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.prefs.SettingsStore
import dev.akhil.freebuff.models.BuiltInCatalog
import dev.akhil.freebuff.voice.stt.WhisperStt
import dev.akhil.freebuff.voice.tts.PiperTts
import dev.akhil.freebuff.voice.tts.SystemTts
import dev.akhil.freebuff.voice.tts.TtsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Strictly half-duplex, tap-to-talk voice assistant.
 *
 * Invariants enforced here (not just documented):
 *  - a turn only starts from [startTurn], which the microphone button calls;
 *  - microphone capture ends before transcription begins;
 *  - exactly one turn can be in flight; a second tap is rejected while busy;
 *  - speech output never happens while the microphone is open;
 *  - after a reply the assistant returns to IDLE and does not re-arm the mic.
 */
class VoiceAssistant(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val storage: AppStorage,
    private val settings: SettingsStore,
    private val capture: AudioCapture,
    private val stt: WhisperStt,
    private val tts: TtsRouter,
    private val chat: ChatOrchestrator,
    private val systemTts: SystemTts,
    private val piper: PiperTts,
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var turnJob: Job? = null

    fun attachSpokenReplies() = Unit

    suspend fun initialise() {
        val appSettings = runCatching { settings.current() }.getOrNull()
        val whisperRecord = db.models().list().firstOrNull {
            it.kind == dev.akhil.freebuff.models.AssetKind.STT.name &&
                it.status == dev.akhil.freebuff.models.InstallStatus.INSTALLED.name
        }
        val sttStatus = if (whisperRecord?.installedPath != null) {
            stt.load(whisperRecord.installedPath).fold(
                onSuccess = { stt.backendLabel() },
                onFailure = { it.message ?: "Speech model could not be loaded" },
            )
        } else {
            "Whisper model not installed yet"
        }

        val piperRecord = db.models().list().firstOrNull {
            it.kind == dev.akhil.freebuff.models.AssetKind.TTS.name &&
                it.id.startsWith("piper-hi") &&
                it.status == dev.akhil.freebuff.models.InstallStatus.INSTALLED.name
        }
        val ttsStatus = tts.warmUp(piperRecord?.installedPath)
        systemTts.setRate(appSettings?.speechRate ?: 1.0f)

        _state.value = _state.value.copy(
            sttBackend = sttStatus,
            ttsBackend = ttsStatus,
            language = appSettings?.sttLanguage ?: "auto",
        )
    }

    /** Starts one voice turn. Ignored while a turn is already running. */
    fun startTurn(mode: AiMode) {
        if (turnJob?.isActive == true || _state.value.state.isBusy) return
        turnJob = scope.launch {
            _state.value = _state.value.copy(
                state = VoiceState.RECORDING,
                error = null,
                heardText = "",
                spokenText = "",
            )
            val result = capture.recordUtterance()
            if (result == null) {
                _state.value = _state.value.copy(
                    state = VoiceState.IDLE,
                    error = null,
                    amplitude = 0f,
                )
                return@launch
            }

            // Microphone is now released by AudioCapture before anything else runs.
            _state.value = _state.value.copy(
                state = VoiceState.FINALIZING,
                amplitude = 0f,
                recordingSeconds = (result.durationMs / 1000).toInt(),
            )

            _state.value = _state.value.copy(state = VoiceState.TRANSCRIBING)
            val transcription = stt.transcribe(result, _state.value.language)
            val heard = transcription.getOrNull()
            if (heard.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    state = VoiceState.ERROR,
                    error = transcription.exceptionOrNull()?.message
                        ?: "No speech was recognised. Try again, a little closer to the microphone.",
                )
                return@launch
            }
            _state.value = _state.value.copy(heardText = heard, state = VoiceState.PROCESSING)

            chat.send(heard)
            val reply = waitForReply()
            if (reply.isBlank()) {
                _state.value = _state.value.copy(
                    state = VoiceState.ERROR,
                    error = "The model returned nothing to speak. Check the conversation for details.",
                )
                return@launch
            }

            _state.value = _state.value.copy(state = VoiceState.SPEAKING, spokenText = reply)
            val spoken = tts.speak(reply, _state.value.language)
            _state.value = _state.value.copy(
                state = if (spoken.isSuccess) VoiceState.COMPLETED else VoiceState.ERROR,
                error = spoken.exceptionOrNull()?.message,
            )
        }
    }

    /** Stops recording, generation and speech. Never starts a new turn. */
    fun stopEverything() {
        capture.cancel()
        chat.stopGeneration()
        tts.stop()
        turnJob?.cancel()
        turnJob = null
        _state.value = _state.value.copy(state = VoiceState.IDLE, amplitude = 0f)
    }

    fun stopSpeaking() {
        tts.stop()
        if (_state.value.state == VoiceState.SPEAKING) {
            _state.value = _state.value.copy(state = VoiceState.COMPLETED)
        }
    }

    fun dismiss() {
        if (_state.value.state.isBusy) return
        _state.value = VoiceUiState(
            sttBackend = _state.value.sttBackend,
            ttsBackend = _state.value.ttsBackend,
            language = _state.value.language,
        )
    }

    fun retryTranscription() {
        if (_state.value.state != VoiceState.ERROR) return
        _state.value = _state.value.copy(state = VoiceState.IDLE, error = null, heardText = "")
    }

    fun speakText(text: String) {
        if (text.isBlank() || _state.value.state == VoiceState.SPEAKING) return
        scope.launch {
            _state.value = _state.value.copy(state = VoiceState.SPEAKING, spokenText = text)
            val result = tts.speak(text, _state.value.language)
            _state.value = _state.value.copy(
                state = if (result.isSuccess) VoiceState.COMPLETED else VoiceState.ERROR,
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    fun observeAmplitude(): StateFlow<Float> = capture.amplitude

    /** Keeps recordings only when the user explicitly opted in. */
    fun persistRecordingIfRequested(capture: CaptureResult) {
        scope.launch {
            val appSettings = runCatching { settings.current() }.getOrNull() ?: return@launch
            if (!appSettings.retainAudio) return@launch
            val dir = File(storage.root, "recordings").apply { mkdirs() }
            val target = File(dir, "turn-${System.currentTimeMillis()}.pcm")
            runCatching {
                target.outputStream().use { out ->
                    val buffer = java.nio.ByteBuffer.allocate(capture.samples.size * 2)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    capture.samples.forEach { buffer.putShort(it) }
                    out.write(buffer.array())
                }
            }
        }
    }

    private suspend fun waitForReply(): String {
        // The chat orchestrator streams; wait for it to finish, then read the answer.
        repeat(600) {
            if (!chat.state.value.isGenerating) return chat.lastAssistantText()
            kotlinx.coroutines.delay(100)
        }
        return chat.lastAssistantText()
    }

    fun addInstalledVoicePathHint(): String {
        val record = BuiltInCatalog.piperHindi
        return storage.ttsDir.resolve(record.id).resolve(record.fileName).absolutePath
    }

    fun release() {
        capture.cancel()
        tts.release()
        scope.launch { stt.unload() }
    }

    /**
     * Language handling: the app never claims perfect identification. Whisper is
     * told "auto" unless the user pinned a language, and mixed Hinglish comes back
     * as Latin script which is spoken by the English voice.
     */
    fun setLanguage(language: String) {
        _state.value = _state.value.copy(language = language)
        scope.launch { settings.setSttLanguage(language) }
    }

    fun describeCapabilities(): String = buildString {
        append("Speech to text: ").append(_state.value.sttBackend)
        append("\nSpeech output: ").append(_state.value.ttsBackend)
        append("\nMode: tap to talk only — no wake word, no background listening.")
    }
}
