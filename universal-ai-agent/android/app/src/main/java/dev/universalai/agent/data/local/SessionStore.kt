package dev.universalai.agent.data.local

import android.content.Context
import android.content.SharedPreferences
import dev.universalai.agent.BuildConfig

/**
 * Local settings. There is no login and no session token: the app only needs to
 * remember which backend it talks to. Provider keys stay on the server.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("universal_agent_settings", Context.MODE_PRIVATE)

    var apiBaseUrl: String
        get() = prefs.getString(KEY_API_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_API_BASE_URL
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value.trim().trimEnd('/')).apply()

    private companion object {
        const val KEY_API_BASE_URL = "api_base_url"
    }
}
