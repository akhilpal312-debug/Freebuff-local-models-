package dev.akhil.freebuff

import android.app.Application
import dev.akhil.freebuff.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FreebuffApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val container = AppContainer.of(this)
        container.bootstrap()
        scope.launch {
            // Reconcile the catalogue with disk on every cold start: a file that
            // vanished (user cleared data, storage error) must never look installed.
            runCatching { container.installer.seedCatalog() }
        }
    }
}
