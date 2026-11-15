package dev.akhil.freebuff.inference

import dev.akhil.freebuff.core.AppStorage
import dev.akhil.freebuff.core.DeviceProfile
import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.db.ModelRecordEntity
import dev.akhil.freebuff.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What is currently resident in memory. At most one local LLM, ever. */
data class ActiveModel(
    val recordId: String,
    val displayName: String,
    val backend: String,
    val contextTokens: Int,
    val remote: Boolean = false,
)

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val modelName: String) : LoadState
    data class Ready(val model: ActiveModel) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * Owns the single active engine.
 *
 * Switching models always unloads the previous one first and waits for the free
 * to complete, so two multi-hundred-megabyte models are never resident together
 * on a phone with 2 GB of RAM.
 */
class EngineManager(
    private val db: AppDatabase,
    private val storage: AppStorage,
    private val devices: DeviceProfile,
    private val secure: SecureStore,
) {
    private val mutex = Mutex()
    private val localEngine = NativeLlamaEngine()
    private val remoteEngine = RemoteEngine(secure)

    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private val _active = MutableStateFlow<ActiveModel?>(null)
    val active: StateFlow<ActiveModel?> = _active.asStateFlow()

    fun engineFor(remote: Boolean): InferenceEngine = if (remote) remoteEngine else localEngine

    fun currentEngine(): InferenceEngine? = _active.value?.let {
        if (it.remote) remoteEngine else localEngine
    }

    fun localAvailable(): Boolean = LlamaNative.available || LlamaNative.retryLoad()

    suspend fun loadLocal(recordId: String, contextTokens: Int, threads: Int): Result<ActiveModel> =
        mutex.withLock {
            val record = db.models().get(recordId)
                ?: return@withLock Result.failure(IllegalStateException("Unknown model."))
            val path = record.installedPath
            if (path == null || !File(path).exists()) {
                val message = "${record.displayName} is not installed. Download it first."
                _state.value = LoadState.Failed(message)
                return@withLock Result.failure(IllegalStateException(message))
            }

            val requiredMb = (record.sizeBytes / (1024 * 1024)) + 180
            if (devices.availableRamBytes / (1024 * 1024) < requiredMb) {
                val message = "Not enough free memory to load ${record.displayName}: " +
                    "about ${requiredMb} MB needed, ${devices.availableRamBytes / (1024 * 1024)} MB available. " +
                    "Close other apps and try again, or pick a smaller model."
                _state.value = LoadState.Failed(message)
                return@withLock Result.failure(IllegalStateException(message))
            }

            _state.value = LoadState.Loading(record.displayName)

            // Release the previous model before allocating the next one.
            if (localEngine.isLoaded()) {
                runCatching { localEngine.unload() }
                _active.value = null
                System.gc()
            }

            when (val result = localEngine.load(path, record.displayName, contextTokens, threads)) {
                is EngineLoad.Success -> {
                    val active = ActiveModel(
                        recordId = record.id,
                        displayName = record.displayName,
                        backend = result.backend,
                        contextTokens = result.contextTokens,
                    )
                    _active.value = active
                    _state.value = LoadState.Ready(active)
                    db.models().updateStatus(record.id, dev.akhil.freebuff.models.InstallStatus.INSTALLED.name, null)
                    Result.success(active)
                }
                is EngineLoad.Failure -> {
                    _state.value = LoadState.Failed(result.message)
                    _active.value = null
                    Result.failure(IllegalStateException(result.message))
                }
            }
        }

    /** Enables an explicitly configured remote provider. Never automatic. */
    suspend fun loadRemote(): Result<ActiveModel> = mutex.withLock {
        val config = remoteEngine.configuration()
            ?: return@withLock Result.failure(
                IllegalStateException("No remote provider is configured. Add one in Settings first."),
            )
        if (localEngine.isLoaded()) {
            runCatching { localEngine.unload() }
        }
        val active = ActiveModel(
            recordId = "remote:${config.model}",
            displayName = "${config.name} · ${config.model}",
            backend = "Remote (online)",
            contextTokens = 8192,
            remote = true,
        )
        _active.value = active
        _state.value = LoadState.Ready(active)
        Result.success(active)
    }

    suspend fun unload(): Unit = mutex.withLock {
        runCatching { localEngine.unload() }
        _active.value = null
        _state.value = LoadState.Idle
        System.gc()
    }

    fun isLoaded(): Boolean = _active.value != null

    fun backendInfo(): String = localEngine.backendInfo()

    fun memorySummary(): String {
        val active = _active.value ?: return "No model in memory"
        return "${active.displayName} · ${active.backend} · ${Format.bytes(devices.availableRamBytes)} RAM free"
    }

    fun describeRecord(record: ModelRecordEntity): String = buildString {
        append(record.quantization)
        append(" · ")
        append(Format.bytes(record.sizeBytes))
        if (record.architecture.isNotBlank()) {
            append(" · ")
            append(record.architecture)
        }
    }
}
