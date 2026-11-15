package dev.akhil.freebuff.core

import android.content.Context
import dev.akhil.freebuff.agent.TaskOrchestrator
import dev.akhil.freebuff.ai.ChatOrchestrator
import dev.akhil.freebuff.ai.ChatRepository
import dev.akhil.freebuff.build.BuildRunner
import dev.akhil.freebuff.data.db.AppDatabase
import dev.akhil.freebuff.data.prefs.SettingsStore
import dev.akhil.freebuff.inference.EngineManager
import dev.akhil.freebuff.models.ModelInstaller
import dev.akhil.freebuff.networking.HuggingFaceClient
import dev.akhil.freebuff.projects.WorkspaceManager
import dev.akhil.freebuff.research.PageFetcher
import dev.akhil.freebuff.research.ResearchEngine
import dev.akhil.freebuff.research.SearchRegistry
import dev.akhil.freebuff.security.SecureStore
import dev.akhil.freebuff.terminal.TerminalRunner
import dev.akhil.freebuff.voice.AudioCapture
import dev.akhil.freebuff.voice.VoiceAssistant
import dev.akhil.freebuff.voice.stt.WhisperStt
import dev.akhil.freebuff.voice.tts.PiperTts
import dev.akhil.freebuff.voice.tts.SystemTts
import dev.akhil.freebuff.voice.tts.TtsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container.
 *
 * A DI framework would add an annotation processor and hundreds of generated
 * classes to every build; on a 2-core machine and a 2 GB phone the plain object
 * graph below is cheaper and easier to follow. Every dependency is exposed as a
 * typed interface where it matters (inference, speech, search) so engines can be
 * swapped without touching call sites.
 */
class AppContainer private constructor(context: Context) {

    val appContext: Context = context.applicationContext

    /** Set when a non-fatal startup step failed, so the UI can say what broke. */
    @Volatile
    var startupError: String? = null

    /** Long-lived scope for work that must survive a screen rotation. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val storage: AppStorage by lazy { AppStorage(appContext) }
    val secure: SecureStore by lazy { SecureStore(appContext) }
    val http: okhttp3.OkHttpClient by lazy { HuggingFaceClient.defaultClient }
    val hf: HuggingFaceClient by lazy { HuggingFaceClient(http) }
    val devices: DeviceProfile by lazy { DeviceInspector.inspect(appContext) }

    val installer: ModelInstaller by lazy {
        ModelInstaller(appContext, db, storage, settings, hf)
    }

    val engines: EngineManager by lazy { EngineManager(db, storage, devices, secure) }

    val chatRepo: ChatRepository by lazy { ChatRepository(db) }

    val workspace: WorkspaceManager by lazy { WorkspaceManager(db, storage) }

    val terminal: TerminalRunner by lazy { TerminalRunner(db, workspace) }

    val searchRegistry: SearchRegistry by lazy { SearchRegistry.create(http) }
    val pageFetcher: PageFetcher by lazy { PageFetcher(http) }

    val research: ResearchEngine by lazy {
        ResearchEngine(appContext, db, searchRegistry, pageFetcher, engines, secure)
    }

    val tasks: TaskOrchestrator by lazy {
        TaskOrchestrator(db, workspace, engines, appScope, appContext)
    }

    val builds: BuildRunner by lazy { BuildRunner(appContext, db, workspace, storage) }

    val audioCapture: AudioCapture by lazy { AudioCapture() }
    val stt: WhisperStt by lazy { WhisperStt() }
    val systemTts: SystemTts by lazy { SystemTts(appContext) }
    val piperTts: PiperTts by lazy { PiperTts(appContext) }
    val ttsRouter: TtsRouter by lazy { TtsRouter(systemTts, piperTts) }

    val chat: ChatOrchestrator by lazy {
        ChatOrchestrator(db, chatRepo, engines, settings, workspace, storage, appScope)
    }

    val voice: VoiceAssistant by lazy {
        VoiceAssistant(
            scope = appScope,
            db = db,
            storage = storage,
            settings = settings,
            capture = audioCapture,
            stt = stt,
            tts = ttsRouter,
            chat = chat,
            systemTts = systemTts,
            piper = piperTts,
        )
    }

    fun bootstrap() {
        runCatching { Notifications.ensureChannels(appContext) }.onFailure {
            CrashReporter.record("bootstrap.channels", it)
        }
        runCatching { storage.cleanStalePartials() }.onFailure {
            CrashReporter.record("bootstrap.partials", it)
        }
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun of(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context).also { instance = it }
        }

        fun peek(): AppContainer? = instance
    }
}
