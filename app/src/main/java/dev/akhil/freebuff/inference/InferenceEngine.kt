package dev.akhil.freebuff.inference

import kotlinx.coroutines.flow.Flow

/** One conversational turn handed to an engine. */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val contextTokens: Int = 2048,
    val threads: Int = 0,
    val seed: Int = -1,
)

data class GenRequest(
    val turns: List<ChatTurn>,
    val params: GenParams,
    /** Tokens the engine should treat as stop markers in addition to the model's EOG. */
    val stopStrings: List<String> = emptyList(),
)

sealed interface GenEvent {
    data class Token(val text: String) : GenEvent
    data class Done(val tokenCount: Int, val elapsedMs: Long, val stopped: Boolean) : GenEvent
    data class Failure(val message: String, val recoverable: Boolean = false) : GenEvent
}

/** Result of a load attempt, with a message a normal user can understand. */
sealed interface EngineLoad {
    data class Success(val backend: String, val contextTokens: Int) : EngineLoad
    data class Failure(val reason: EngineFailure, val message: String) : EngineLoad
}

enum class EngineFailure {
    NOT_BUILT,
    FILE_MISSING,
    UNSUPPORTED_ARCHITECTURE,
    OUT_OF_MEMORY,
    LOAD_ERROR,
    BUSY,
}

/**
 * Everything the rest of the app knows about a model runtime.
 *
 * Implementations: [NativeLlamaEngine] (bundled llama.cpp through JNI) and
 * [RemoteEngine] (user-configured, explicitly online). There is deliberately no
 * "canned response" implementation: if no engine can run, the UI says so.
 */
interface InferenceEngine {
    val id: String
    val displayName: String
    /** True when this engine sends data off the device. */
    val isRemote: Boolean

    val loadedModelPath: String?
    val loadedModelName: String?

    suspend fun load(modelPath: String, modelName: String, contextTokens: Int, threads: Int): EngineLoad
    suspend fun unload()
    fun isLoaded(): Boolean

    fun stream(request: GenRequest): Flow<GenEvent>

    /** Cooperative cancellation for the active generation. */
    fun stop()

    /** Human-readable backend description shown in the UI. */
    fun backendInfo(): String
}
