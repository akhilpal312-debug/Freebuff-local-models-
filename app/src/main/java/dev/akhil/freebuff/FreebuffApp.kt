package dev.akhil.freebuff

import android.app.Application
import dev.akhil.freebuff.core.AppContainer
import dev.akhil.freebuff.core.CrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FreebuffApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Installed first, so a failure in our own startup is recorded rather
        // than showing up as an unexplained close.
        CrashReporter.install(this)

        // Nothing in onCreate may throw. A phone that cannot start the app is a
        // worse outcome than an app that starts with one feature degraded, so
        // every step is isolated and anything missing is reported in the UI
        // (see AppContainer.startupError).
        runCatching { AppContainer.of(this) }.onFailure {
            CrashReporter.record("application.create-container", it)
        }

        val container = AppContainer.peek() ?: return
        runCatching { container.bootstrap() }.onFailure {
            CrashReporter.record("application.bootstrap", it)
            container.startupError = it.message ?: it.javaClass.simpleName
        }

        scope.launch {
            // Reconcile the catalogue with disk on every cold start: a file that
            // vanished (user cleared data, storage error) must never look installed.
            runCatching { container.installer.seedCatalog() }.onFailure {
                CrashReporter.record("application.seed-catalog", it)
            }
        }
    }
}
