package dev.akhil.freebuff.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.akhil.freebuff.ai.AiMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "freebuff_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val activeModelId: String = "gemma-3-270m-it",
    val defaultMode: AiMode = AiMode.FAST_CHAT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingComplete: Boolean = false,
    val wifiOnlyDownloads: Boolean = true,
    val allowMeteredDownloads: Boolean = false,
    val autoLoadLastModel: Boolean = true,
    val contextTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int = 512,
    val threads: Int = 0,
    val ttsVoice: String = "en",
    val sttLanguage: String = "auto",
    val speechRate: Float = 1.0f,
    val retainAudio: Boolean = false,
    val lastConversationId: String? = null,
    val terminalSessionId: String? = null,
    val remoteProviderEnabled: Boolean = false,
    val remoteProviderName: String = "",
    val remoteProviderModel: String = "",
    val remoteProviderBaseUrl: String = "",
    val searchProviderId: String = "duckduckgo",
    val deepResearchMaxSources: Int = 8,
    val allowRemoteBuildUpload: Boolean = false,
    val gradleHeapMb: Int = 1024,
) {
    companion object {
        const val DEFAULT_MODEL_ID = "gemma-3-270m-it"
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val activeModel = stringPreferencesKey("active_model")
        val defaultMode = stringPreferencesKey("default_mode")
        val themeMode = stringPreferencesKey("theme_mode")
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val metered = booleanPreferencesKey("allow_metered")
        val autoLoad = booleanPreferencesKey("auto_load_model")
        val contextTokens = intPreferencesKey("context_tokens")
        val temperature = floatPreferencesKey("temperature")
        val maxOutput = intPreferencesKey("max_output_tokens")
        val threads = intPreferencesKey("threads")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val sttLanguage = stringPreferencesKey("stt_language")
        val speechRate = floatPreferencesKey("speech_rate")
        val retainAudio = booleanPreferencesKey("retain_audio")
        val lastConversation = stringPreferencesKey("last_conversation")
        val terminalSession = stringPreferencesKey("terminal_session")
        val remoteEnabled = booleanPreferencesKey("remote_enabled")
        val remoteName = stringPreferencesKey("remote_name")
        val remoteModel = stringPreferencesKey("remote_model")
        val remoteBaseUrl = stringPreferencesKey("remote_base_url")
        val searchProvider = stringPreferencesKey("search_provider")
        val researchSources = intPreferencesKey("research_sources")
        val remoteBuild = booleanPreferencesKey("remote_build_upload")
        val gradleHeap = intPreferencesKey("gradle_heap")
        val lastModelBytes = longPreferencesKey("last_model_bytes")
    }

    private val prefs: Flow<Preferences> get() = context.dataStore.data

    val settings: Flow<AppSettings> = prefs.map { p ->
        AppSettings(
            activeModelId = p[Keys.activeModel] ?: AppSettings.DEFAULT_MODEL_ID,
            defaultMode = p[Keys.defaultMode]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }
                ?: AiMode.FAST_CHAT,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            onboardingComplete = p[Keys.onboarding] ?: false,
            wifiOnlyDownloads = p[Keys.wifiOnly] ?: true,
            allowMeteredDownloads = p[Keys.metered] ?: false,
            autoLoadLastModel = p[Keys.autoLoad] ?: true,
            contextTokens = p[Keys.contextTokens] ?: 2048,
            temperature = p[Keys.temperature] ?: 0.7f,
            maxOutputTokens = p[Keys.maxOutput] ?: 512,
            threads = p[Keys.threads] ?: 0,
            ttsVoice = p[Keys.ttsVoice] ?: "en",
            sttLanguage = p[Keys.sttLanguage] ?: "auto",
            speechRate = p[Keys.speechRate] ?: 1.0f,
            retainAudio = p[Keys.retainAudio] ?: false,
            lastConversationId = p[Keys.lastConversation],
            terminalSessionId = p[Keys.terminalSession],
            remoteProviderEnabled = p[Keys.remoteEnabled] ?: false,
            remoteProviderName = p[Keys.remoteName] ?: "",
            remoteProviderModel = p[Keys.remoteModel] ?: "",
            remoteProviderBaseUrl = p[Keys.remoteBaseUrl] ?: "",
            searchProviderId = p[Keys.searchProvider] ?: "duckduckgo",
            deepResearchMaxSources = p[Keys.researchSources] ?: 8,
            allowRemoteBuildUpload = p[Keys.remoteBuild] ?: false,
            gradleHeapMb = p[Keys.gradleHeap] ?: 1024,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setActiveModel(id: String) = edit { it[Keys.activeModel] = id }
    suspend fun setDefaultMode(mode: AiMode) = edit { it[Keys.defaultMode] = mode.name }
    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }
    suspend fun setOnboardingComplete(done: Boolean) = edit { it[Keys.onboarding] = done }
    suspend fun setWifiOnly(value: Boolean) = edit { it[Keys.wifiOnly] = value }
    suspend fun setAllowMetered(value: Boolean) = edit { it[Keys.metered] = value }
    suspend fun setAutoLoad(value: Boolean) = edit { it[Keys.autoLoad] = value }
    suspend fun setContextTokens(value: Int) = edit { it[Keys.contextTokens] = value }
    suspend fun setTemperature(value: Float) = edit { it[Keys.temperature] = value }
    suspend fun setMaxOutputTokens(value: Int) = edit { it[Keys.maxOutput] = value }
    suspend fun setThreads(value: Int) = edit { it[Keys.threads] = value }
    suspend fun setTtsVoice(value: String) = edit { it[Keys.ttsVoice] = value }
    suspend fun setSttLanguage(value: String) = edit { it[Keys.sttLanguage] = value }
    suspend fun setSpeechRate(value: Float) = edit { it[Keys.speechRate] = value }
    suspend fun setRetainAudio(value: Boolean) = edit { it[Keys.retainAudio] = value }
    suspend fun setLastConversation(id: String?) = edit {
        if (id == null) it.remove(Keys.lastConversation) else it[Keys.lastConversation] = id
    }
    suspend fun setTerminalSession(id: String) = edit { it[Keys.terminalSession] = id }
    suspend fun setRemoteProvider(enabled: Boolean, name: String, model: String, baseUrl: String) = edit {
        it[Keys.remoteEnabled] = enabled
        it[Keys.remoteName] = name
        it[Keys.remoteModel] = model
        it[Keys.remoteBaseUrl] = baseUrl
    }
    suspend fun setSearchProvider(id: String) = edit { it[Keys.searchProvider] = id }
    suspend fun setResearchSources(count: Int) = edit { it[Keys.researchSources] = count }
    suspend fun setRemoteBuildUpload(value: Boolean) = edit { it[Keys.remoteBuild] = value }
    suspend fun setGradleHeap(value: Int) = edit { it[Keys.gradleHeap] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
