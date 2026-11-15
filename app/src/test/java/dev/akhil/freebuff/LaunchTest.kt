package dev.akhil.freebuff

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.akhil.freebuff.ui.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole cold-start path, headless.
 *
 * A phone that shows the splash and immediately closes is the worst failure this
 * app can have, and it is exactly what a device-less build cannot catch by
 * compiling. This test runs the real Application, the real dependency container,
 * the real Activity and the real Compose tree on Robolectric, so any exception
 * thrown during startup fails the build instead of the user's launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = FreebuffApp::class)
class LaunchTest {

    @Test
    fun applicationStarts() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertTrue("Application class was not created", application is FreebuffApp)
        assertNotNull("AppContainer was not initialised", dev.akhil.freebuff.core.AppContainer.peek())
    }

    @Test
    fun mainActivityReachesResumed() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertNotNull("Activity has no window", activity.window)
        controller.pause().stop().destroy()
    }

    @Test
    fun mainActivitySurvivesRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.recreate().get()
        controller.destroy()
    }
}
