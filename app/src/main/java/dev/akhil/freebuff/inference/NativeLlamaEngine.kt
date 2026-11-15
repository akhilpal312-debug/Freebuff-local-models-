package dev.akhil.freebuff.inference

import dev.akhil.freebuff.core.Format
import dev.akhil.freebuff.models.GgufInspector
import dev.akhil.freebuff.models.SupportedArchitectures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp-backed on-device engine.
 *
 * One model at a time, one generation at a time: both are enforced here rather
 * than trusted to the caller, because an OOM on a 2 GB phone kills the whole app.
 */
class NativeLlamaEngine : InferenceEngine {

    override val id: String = "llama.cpp"
    override val displayName: String = "llama.cpp (on-device)"
    override val isRemote: Boolean = false

    @Volatile private var handle: Long = 0L
    @Volatile private var modelPath: String? = null
    @Volatile private var modelName: String? = null
    @Volatile private var contextSize: Int = 2048
    private val generating = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    override val loadedModelPath: String? get() = modelPath
    override val loadedModelName: String? get() = modelName

    override fun isLoaded(): Boolean = handle != 0L

    override suspend fun load(
        modelPath: String,
        modelName: String,
        contextTokens: Int,
        threads: Int,
    ): EngineLoad = withContext(Dispatchers.IO) {
        val loadedName = modelName
        if (!LlamaNative.available && !LlamaNative.retryLoad()) {
            return@withContext EngineLoad.Failure(
                EngineFailure.NOT_BUILT,
                "Local inference is not included in this build of Freebuff Mobile. " +
                    "Rebuild with the native engine enabled (-PwithNative=true) and a llama.cpp checkout. " +
                    "Nothing was generated.",
            )
        }
        val file = File(modelPath)
        if (!file.exists()) {
            return@withContext EngineLoad.Failure(EngineFailure.FILE_MISSING, "Model file not found: ${file.name}")
        }

        val info = GgufInspector.inspect(file).getOrElse { error ->
            return@withContext EngineLoad.Failure(
                EngineFailure.LOAD_ERROR,
                "This file is not a readable GGUF model: ${error.message}",
            )
        }
        if (!info.architectureSupported) {
            return@withContext EngineLoad.Failure(
                EngineFailure.UNSUPPORTED_ARCHITECTURE,
                SupportedArchitectures.explanation(info.architecture),
            )
        }

        if (isLoaded()) unload()

        val effectiveThreads = if (threads > 0) threads else defaultThreads()
        val ctx = contextTokens.coerceIn(512, 4096)
        val native = LlamaNative.nativeLoadModel(file.absolutePath, ctx, effectiveThreads, true)
        if (native == 0L) {
            val reason = runCatching { LlamaNative.nativeLastError() }.getOrNull()
            return@withContext EngineLoad.Failure(
                if (reason?.contains("memory", true) == true) EngineFailure.OUT_OF_MEMORY else EngineFailure.LOAD_ERROR,
                reason?.ifBlank { "llama.cpp could not load ${file.name}." }
                    ?: "llama.cpp could not load ${file.name}.",
            )
        }
        handle = native
        this@NativeLlamaEngine.modelPath = file.absolutePath
        this@NativeLlamaEngine.modelName = loadedName
        this@NativeLlamaEngine.contextSize = ctx
        EngineLoad.Success(
            backend = "llama.cpp · ${effectiveThreads} threads · ${info.quantization}",
            contextTokens = ctx,
        )
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        val current = handle
        handle = 0
        modelPath = null
        modelName = null
        if (current != 0L) {
            runCatching { LlamaNative.nativeFreeModel(current) }
        }
    }

    override fun stream(request: GenRequest): Flow<GenEvent> = callbackFlow {
        val current = handle
        if (current == 0L) {
            trySend(
                GenEvent.Failure(
                    "No model is loaded. Open Models and load one first.",
                ),
            )
            close()
            return@callbackFlow
        }
        if (!generating.compareAndSet(false, true)) {
            trySend(GenEvent.Failure("A generation is already running. Stop it before starting another."))
            close()
            return@callbackFlow
        }
        stopRequested.set(false)
        val startedAt = System.currentTimeMillis()
        var count = 0
        val prompt = PromptRenderer.render(request.turns, modelName ?: "")

        val sink = object : TokenSink {
            override fun onToken(text: String): Boolean {
                if (stopRequested.get()) return false
                val visible = request.stopStrings.fold(text) { acc, stop -> acc.replace(stop, "") }
                if (visible.isNotEmpty()) {
                    count++
                    trySend(GenEvent.Token(visible))
                }
                return !stopRequested.get()
            }
        }

        try {
            val produced = LlamaNative.nativeGenerate(
                current,
                prompt,
                request.params.maxTokens,
                request.params.temperature,
                request.params.topP,
                request.params.topK,
                request.params.seed,
                sink,
            )
            if (produced < 0) {
                val reason = runCatching { LlamaNative.nativeLastError() }.getOrNull()
                trySend(GenEvent.Failure(reason?.ifBlank { "Generation failed inside llama.cpp." } ?: "Generation failed."))
            } else {
                trySend(
                    GenEvent.Done(
                        tokenCount = produced,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        stopped = stopRequested.get(),
                    ),
                )
            }
        } catch (error: Throwable) {
            trySend(GenEvent.Failure(error.message ?: "Native generation crashed: ${error.javaClass.simpleName}"))
        } finally {
            generating.set(false)
            close()
        }
        awaitClose { stopRequested.set(true) }
    }.flowOn(Dispatchers.Default)

    override fun stop() {
        stopRequested.set(true)
        val current = handle
        if (current != 0L) runCatching { LlamaNative.nativeStop(current) }
    }

    override fun backendInfo(): String {
        if (!LlamaNative.available) {
            return "llama.cpp not present in this build" +
                (LlamaNative.loadError?.let { " ($it)" } ?: "")
        }
        val info = runCatching { LlamaNative.nativeBackendInfo() }.getOrNull()
        val model = modelName ?: "no model loaded"
        return "${info?.ifBlank { "llama.cpp" } ?: "llama.cpp"} · $model · ${contextSize} ctx"
    }

    private fun defaultThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Leave one core for the UI thread; never exceed 4 on low-end phones.
        return (cores - 1).coerceIn(1, 4)
    }

    fun memoryHint(): String =
        if (isLoaded()) "Loaded: ${modelName ?: "model"} (${Format.bytes(contextSize.toLong())} context)"
        else "No model loaded"
}
