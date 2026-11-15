package dev.akhil.freebuff.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.akhil.freebuff.ai.AiMode
import dev.akhil.freebuff.core.AppContainer
import dev.akhil.freebuff.core.DeviceProfile
import dev.akhil.freebuff.data.db.ConversationEntity
import dev.akhil.freebuff.data.db.ModelRecordEntity
import dev.akhil.freebuff.data.db.ProjectEntity
import dev.akhil.freebuff.data.db.ResearchSessionEntity
import dev.akhil.freebuff.data.db.TaskEntity
import dev.akhil.freebuff.data.prefs.AppSettings
import dev.akhil.freebuff.data.prefs.ThemeMode
import dev.akhil.freebuff.inference.ActiveModel
import dev.akhil.freebuff.inference.LoadState
import dev.akhil.freebuff.models.BuiltInCatalog
import dev.akhil.freebuff.research.ResearchProgress
import dev.akhil.freebuff.voice.VoiceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One view model for the app shell.
 *
 * Screens read state from the container's own StateFlows where they exist (chat,
 * voice, research) and from this class for database-backed lists, so a screen
 * rotation never restarts a download, a generation or a task.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val container: AppContainer = AppContainer.of(application)
    val device: DeviceProfile = container.devices

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val models: StateFlow<List<ModelRecordEntity>> = container.installer.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val conversations: StateFlow<List<ConversationEntity>> = container.chatRepo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projects: StateFlow<List<ProjectEntity>> = container.workspace.observeProjects()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tasks: StateFlow<List<TaskEntity>> = container.tasks.observeTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val researchSessions: StateFlow<List<ResearchSessionEntity>> = container.research.observeSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Convenience handles so screens can reach the long-lived coordinators. */
    val chat: dev.akhil.freebuff.ai.ChatOrchestrator get() = container.chat
    val voice: dev.akhil.freebuff.voice.VoiceAssistant get() = container.voice

    val engineState: StateFlow<LoadState> = container.engines.state
    val activeModel: StateFlow<ActiveModel?> = container.engines.active
    val chatState = container.chat.state
    val voiceState: StateFlow<VoiceUiState> = container.voice.state
    val researchProgress: StateFlow<ResearchProgress> = container.research.progress

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.installer.seedCatalog() }
                .onFailure { dev.akhil.freebuff.core.CrashReporter.record("viewmodel.seed", it) }
            val appSettings = runCatching { container.settings.current() }.getOrNull()
                ?: return@launch
            if (!appSettings.onboardingComplete) return@launch
            if (appSettings.autoLoadLastModel) {
                // Loading a model can fail for many honest reasons (missing file,
                // not enough RAM, no native build). None of them may kill startup.
                runCatching {
                    val installed = models.value.filter {
                        it.kind == "LLM" && it.status == "INSTALLED" && it.installedPath != null
                    }
                    val preferred = installed.firstOrNull { it.id == appSettings.activeModelId }
                        ?: installed.firstOrNull()
                    if (preferred != null && container.engines.active.value == null) {
                        container.engines.loadLocal(
                            preferred.id,
                            appSettings.contextTokens,
                            appSettings.threads,
                        )
                    }
                }.onFailure { dev.akhil.freebuff.core.CrashReporter.record("viewmodel.autoload", it) }
            }
            runCatching { container.voice.initialise() }
                .onFailure { dev.akhil.freebuff.core.CrashReporter.record("viewmodel.voice", it) }
            val last = appSettings.lastConversationId
            if (last != null) {
                runCatching { container.chat.open(last) }
                    .onFailure { dev.akhil.freebuff.core.CrashReporter.record("viewmodel.open-conversation", it) }
            }
        }
    }

    // ---- navigation helpers -------------------------------------------

    fun openConversation(id: String) {
        container.chat.open(id)
        viewModelScope.launch { container.settings.setLastConversation(id) }
    }

    fun newConversation(): String {
        var id = ""
        viewModelScope.launch {
            val conversation = container.chatRepo.createConversation(
                modelId = activeModel.value?.recordId ?: settings.value.activeModelId,
                mode = settings.value.defaultMode,
            )
            id = conversation.id
            container.chat.open(conversation.id)
            container.settings.setLastConversation(conversation.id)
        }
        return id
    }

    fun renameConversation(id: String, title: String) = viewModelScope.launch {
        container.chatRepo.rename(id, title)
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        container.chatRepo.delete(id)
        if (chatState.value.conversationId == id) container.chat.close()
    }

    // ---- models -------------------------------------------------------

    fun installEverything() = viewModelScope.launch {
        _busy.value = "Preparing downloads…"
        val result = container.installer.enqueueInstall()
        _busy.value = null
        _toast.value = result.reason ?: "Queued ${result.queued} download(s). They continue in the background."
    }

    fun installModel(id: String) = viewModelScope.launch {
        val spec = BuiltInCatalog.find(id)
        if (spec == null) {
            val result = container.installer.retry(id)
            _toast.value = result.reason ?: "Queued."
            return@launch
        }
        val result = container.installer.enqueueInstall(listOf(spec))
        _toast.value = result.reason ?: "Queued ${spec.displayName}."
    }

    fun pauseDownloads() {
        container.installer.pauseDownloads()
        _toast.value = "Downloads paused. Partial files are kept so they can resume."
    }

    fun resumeDownloads() = viewModelScope.launch {
        val result = container.installer.resumeDownloads()
        _toast.value = result.reason ?: "Resumed ${result.queued} download(s)."
    }

    fun deleteModel(id: String) = viewModelScope.launch {
        if (activeModel.value?.recordId == id) container.engines.unload()
        container.installer.delete(id).fold(
            onSuccess = { _toast.value = "Model deleted from this device." },
            onFailure = { _toast.value = it.message },
        )
    }

    fun loadModel(id: String) = viewModelScope.launch {
        _busy.value = "Loading model…"
        val result = container.engines.loadLocal(
            id,
            settings.value.contextTokens,
            settings.value.threads,
        )
        _busy.value = null
        result.fold(
            onSuccess = {
                container.settings.setActiveModel(id)
                container.chat.refreshModelLabel()
                _toast.value = "Loaded ${it.displayName}."
            },
            onFailure = { _toast.value = it.message },
        )
    }

    fun unloadModel() = viewModelScope.launch {
        container.engines.unload()
        container.chat.refreshModelLabel()
        _toast.value = "Model unloaded. Memory released."
    }

    fun importModel(uri: android.net.Uri, name: String) = viewModelScope.launch {
        _busy.value = "Validating model…"
        val result = container.installer.importFromUri(uri, name)
        _busy.value = null
        result.fold(
            onSuccess = { _toast.value = "Imported ${it.displayName} (${it.architecture}, ${it.quantization})." },
            onFailure = { _toast.value = it.message },
        )
    }

    fun addHuggingFaceModel(repo: String, file: String, name: String) = viewModelScope.launch {
        _busy.value = "Checking repository…"
        val result = container.installer.addFromHuggingFace(repo, file, name)
        _busy.value = null
        result.fold(
            onSuccess = {
                container.installer.retry(it.id)
                _toast.value = "Queued ${it.displayName} for download."
            },
            onFailure = { _toast.value = it.message },
        )
    }

    // ---- projects -----------------------------------------------------

    fun createProject(name: String, template: dev.akhil.freebuff.projects.ProjectTemplate, onDone: (String) -> Unit) =
        viewModelScope.launch {
            _busy.value = "Creating project…"
            val type = dev.akhil.freebuff.projects.ProjectTemplates.typeFor(template)
            val result = container.workspace.createProject(name, type, template)
            _busy.value = null
            result.fold(
                onSuccess = { onDone(it.id) },
                onFailure = { _toast.value = it.message },
            )
        }

    fun deleteProject(id: String) = viewModelScope.launch {
        container.workspace.deleteProject(id).fold(
            onSuccess = { _toast.value = "Project deleted." },
            onFailure = { _toast.value = it.message },
        )
    }

    fun renameProject(id: String, name: String) = viewModelScope.launch {
        container.workspace.renameProject(id, name)
    }

    // ---- tasks --------------------------------------------------------

    fun submitTask(request: String, mode: AiMode, projectId: String?) = viewModelScope.launch {
        val model = activeModel.value
        if (model == null) {
            _toast.value = "Load a model first — the agent needs one to generate content."
            return@launch
        }
        container.tasks.submit(request, mode, projectId, model.recordId)
        _toast.value = "Task queued. Watch it in Tasks."
    }

    fun cancelTask(id: String) = container.tasks.cancel(id)

    fun retryTask(task: TaskEntity) {
        if (activeModel.value == null) {
            _toast.value = "Load a model first."
            return
        }
        container.tasks.retry(task)
    }

    fun deleteTask(id: String) = viewModelScope.launch { container.db.tasks().delete(id) }

    fun clearFinishedTasks() = viewModelScope.launch { container.db.tasks().clearFinished() }

    // ---- research -----------------------------------------------------

    fun startResearch(question: String) = viewModelScope.launch {
        _toast.value = null
        val provider = settings.value.searchProviderId
        container.research.run(
            question = question,
            maxSources = settings.value.deepResearchMaxSources,
            preferredProvider = provider,
            modelContextTokens = settings.value.contextTokens,
        )
    }

    // ---- terminal -----------------------------------------------------

    fun runTerminalCommand(projectId: String, sessionId: String, command: String, confirmed: Boolean = false) =
        viewModelScope.launch {
            val project = container.workspace.getProject(projectId) ?: run {
                _toast.value = "Open a project first."
                return@launch
            }
            container.terminal.run(project, sessionId, command, confirmed)
        }

    // ---- settings -----------------------------------------------------

    fun setActiveMode(mode: AiMode) {
        container.chat.setMode(mode)
        viewModelScope.launch { container.settings.setDefaultMode(mode) }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setTheme(mode) }

    fun setSearchProvider(id: String) = viewModelScope.launch { container.settings.setSearchProvider(id) }

    fun setSearchKey(value: String) {
        container.secure.put(dev.akhil.freebuff.security.SecureStore.KEY_SEARCH_API, value)
        _toast.value = if (value.isBlank()) "Search key removed." else "Search key stored in the Android Keystore."
    }

    fun setRemoteProvider(enabled: Boolean, name: String, model: String, baseUrl: String, apiKey: String) =
        viewModelScope.launch {
            container.settings.setRemoteProvider(enabled, name, model, baseUrl)
            if (apiKey.isNotBlank()) {
                container.secure.put(dev.akhil.freebuff.security.SecureStore.KEY_REMOTE_API, apiKey)
            }
            container.engines.engineFor(true).let { engine ->
                (engine as? dev.akhil.freebuff.inference.RemoteEngine)?.configure(
                    dev.akhil.freebuff.inference.RemoteConfig(
                        name = name,
                        baseUrl = baseUrl,
                        model = model,
                        apiKey = if (apiKey.isNotBlank()) {
                            apiKey
                        } else {
                            container.secure.get(dev.akhil.freebuff.security.SecureStore.KEY_REMOTE_API)
                        },
                    ),
                )
            }
            _toast.value = if (enabled) "Remote provider configured. Replies are marked as online." else "Remote provider disabled."
        }

    fun setWifiOnly(value: Boolean) = viewModelScope.launch { container.settings.setWifiOnly(value) }
    fun setAllowMetered(value: Boolean) = viewModelScope.launch { container.settings.setAllowMetered(value) }
    fun setAutoLoad(value: Boolean) = viewModelScope.launch { container.settings.setAutoLoad(value) }
    fun setContextTokens(value: Int) = viewModelScope.launch { container.settings.setContextTokens(value) }
    fun setTemperature(value: Float) = viewModelScope.launch { container.settings.setTemperature(value) }
    fun setMaxOutput(value: Int) = viewModelScope.launch { container.settings.setMaxOutputTokens(value) }
    fun setRetainAudio(value: Boolean) = viewModelScope.launch { container.settings.setRetainAudio(value) }

    fun setSpeechRate(value: Float) {
        container.systemTts.setRate(value)
        viewModelScope.launch { container.settings.setSpeechRate(value) }
    }

    fun completeOnboarding() = viewModelScope.launch {
        container.settings.setOnboardingComplete(true)
    }

    fun deleteAllChats() = viewModelScope.launch {
        container.chatRepo.clearAll()
        container.chat.close()
        _toast.value = "All conversations deleted."
    }

    fun clearToast() {
        _toast.value = null
    }

    fun showToast(message: String) {
        _toast.value = message
    }

    override fun onCleared() {
        super.onCleared()
        container.chat.close()
        container.voice.release()
    }
}
