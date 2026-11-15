package dev.akhil.freebuff.voice

/**
 * Half-duplex voice states. Only one turn at a time, and the microphone is
 * released before transcription, processing or speech begins.
 */
enum class VoiceState {
    IDLE,
    RECORDING,
    FINALIZING,
    TRANSCRIBING,
    PROCESSING,
    SPEAKING,
    COMPLETED,
    ERROR,
    ;

    val isBusy: Boolean
        get() = this == RECORDING || this == FINALIZING || this == TRANSCRIBING ||
            this == PROCESSING || this == SPEAKING

    val microphonesAllowed: Boolean get() = this == IDLE || this == COMPLETED || this == ERROR

    val label: String
        get() = when (this) {
            IDLE -> "Tap the microphone to talk"
            RECORDING -> "Listening — tap stop when you finish"
            FINALIZING -> "Finishing the recording"
            TRANSCRIBING -> "Transcribing on device"
            PROCESSING -> "Thinking"
            SPEAKING -> "Speaking — microphone is off"
            COMPLETED -> "Ready for another turn"
            ERROR -> "Something went wrong"
        }
}

data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val amplitude: Float = 0f,
    val heardText: String = "",
    val spokenText: String = "",
    val error: String? = null,
    val recordingSeconds: Int = 0,
    val language: String = "auto",
    val sttBackend: String = "Not loaded",
    val ttsBackend: String = "Not loaded",
)
