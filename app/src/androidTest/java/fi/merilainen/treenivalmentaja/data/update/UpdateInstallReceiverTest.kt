package fi.merilainen.treenivalmentaja.data.update

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.merilainen.treenivalmentaja.MainActivity
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing this receiver does that could be turned against the app: it starts an `Intent` it
 * was handed.
 *
 * `PackageInstaller` reports `STATUS_PENDING_USER_ACTION` by putting the confirmation activity in
 * `Intent.EXTRA_INTENT`, and the receiver launches it. That is safe only because the receiver is
 * `exported="false"` and is reached by an explicit `PendingIntent`, so the system is the only
 * sender — but "only for the status that means it" has to be true in the code as well, or a
 * delivery with any other status would launch whatever it carried. Instrumented rather than
 * Robolectric because what is being asserted is an actual activity launch.
 *
 * `MainActivity` stands in for the system's install prompt: it is a real activity of this app, so
 * a launch can be observed, and unlike the platform dialog it can be started from a test.
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallReceiverTest {

    private lateinit var app: TreenivalmentajaApplication
    private lateinit var instrumentation: Instrumentation
    private lateinit var monitor: Instrumentation.ActivityMonitor
    private val receiver = UpdateInstallReceiver()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        instrumentation = InstrumentationRegistry.getInstrumentation()
        // Named by class rather than by filter, and blocking: the launch is counted and stopped,
        // so the test never actually puts a screen in front of the next test.
        monitor = instrumentation.addMonitor(
            MainActivity::class.java.name,
            Instrumentation.ActivityResult(0, null),
            true,
        )
    }

    @After
    fun tearDown() {
        instrumentation.removeMonitor(monitor)
    }

    private fun callback(status: Int, action: String? = UpdateInstallReceiver.ACTION_INSTALL_STATUS) =
        Intent(action).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, status)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, 1234)
            putExtra(Intent.EXTRA_INTENT, Intent(app, MainActivity::class.java))
        }

    /** What the real flow does: the confirmation Android asked for is opened. */
    @Test
    fun pendingUserActionStartsTheConfirmation() {
        instrumentation.runOnMainSync {
            receiver.onReceive(app, callback(PackageInstaller.STATUS_PENDING_USER_ACTION))
        }

        assertEquals(1, monitor.hits)
    }

    /** A finished install carries no confirmation to open, whatever else is in the intent. */
    @Test
    fun aTerminalStatusStartsNothing() {
        instrumentation.runOnMainSync {
            receiver.onReceive(app, callback(PackageInstaller.STATUS_FAILURE_ABORTED))
        }

        assertEquals(0, monitor.hits)
    }

    /**
     * The forgery case. The receiver is not exported, so this delivery cannot happen from outside
     * the app — but if the action guard were dropped, nothing else here would stop it.
     */
    @Test
    fun anIntentWithAnotherActionIsIgnored() {
        instrumentation.runOnMainSync {
            receiver.onReceive(
                app,
                callback(
                    PackageInstaller.STATUS_PENDING_USER_ACTION,
                    action = "fi.merilainen.treenivalmentaja.SPOOFED",
                ),
            )
        }

        assertEquals(0, monitor.hits)
    }

    @Test
    fun anIntentWithNoConfirmationStartsNothing() {
        val withoutConfirmation =
            Intent(UpdateInstallReceiver.ACTION_INSTALL_STATUS).apply {
                putExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_PENDING_USER_ACTION,
                )
            }

        instrumentation.runOnMainSync { receiver.onReceive(app, withoutConfirmation) }

        assertEquals(0, monitor.hits)
    }
}
