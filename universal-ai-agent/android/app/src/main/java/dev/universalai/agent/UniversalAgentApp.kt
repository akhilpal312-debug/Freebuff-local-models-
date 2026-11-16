package dev.universalai.agent

import android.app.Application
import dev.universalai.agent.core.AppContainer

class UniversalAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
