package fi.merilainen.treenivalmentaja.data.update

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import fi.merilainen.treenivalmentaja.data.alarm.BootReceiver
import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installing an update kills the process, so the only thing the app can do about coming back is
 * put itself one tap away. This is that tap — and it has to appear for exactly one of the three
 * actions `BootReceiver` handles: a reboot and a timezone change replace nothing.
 *
 * Instrumented because it asserts what the system's notification manager is actually holding, and
 * because `POST_NOTIFICATIONS` is a real runtime permission on this API level.
 *
 * **Posting and reading are separate calls into the system**, so a read taken immediately after
 * `notify()` can beat the post to it — which is exactly how this test first failed while the
 * notification was being delivered perfectly well. Presence is therefore polled and absence is
 * asserted after a settle, rather than either being read once.
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstalledNotificationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private lateinit var app: TreenivalmentajaApplication
    private lateinit var notifications: NotificationManager
    private val receiver = BootReceiver()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        notifications = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        clearNotices()
    }

    @After
    fun tearDown() {
        clearNotices()
    }

    private fun updateNotices(): List<StatusBarNotification> =
        notifications.activeNotifications.filter {
            it.notification.channelId == NotificationChannels.APP_UPDATES
        }

    /** Waited on rather than assumed: a notice left by the previous test would fake a pass. */
    private fun clearNotices() {
        notifications.cancelAll()
        repeat(POLLS) {
            if (updateNotices().isEmpty()) return
            Thread.sleep(POLL_MS)
        }
    }

    private fun awaitNotice(): StatusBarNotification? {
        repeat(POLLS) {
            updateNotices().singleOrNull()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /** Long enough that a notice on its way would have arrived before absence is asserted. */
    private fun settle() = Thread.sleep(POLLS * POLL_MS / 2)

    @Test
    fun replacingThePackageOffersTheWayBackIn() {
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val notice = awaitNotice()
        assertNotNull("expected an update notice", notice)
        // Tapping it is the whole point; a notice with nothing behind it would be worse than none.
        assertNotNull("the notice must open the app", notice!!.notification.contentIntent)
    }

    /** A phone that has rebooted has not been updated, and must not be told that it has. */
    @Test
    fun aRebootPostsNothing() {
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        settle()

        assertEquals(0, updateNotices().size)
    }

    @Test
    fun aTimezoneChangePostsNothing() {
        receiver.onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        settle()

        assertEquals(0, updateNotices().size)
    }

    /** The manifest filter is the contract; an action outside it is acted on in no way at all. */
    @Test
    fun anIntentWithAnotherActionPostsNothing() {
        receiver.onReceive(app, Intent("fi.merilainen.treenivalmentaja.SPOOFED"))
        settle()

        assertEquals(0, updateNotices().size)
    }

    /** Two updates in a row leave one notice, not a pile: the id is fixed, so the second replaces. */
    @Test
    fun aSecondUpdateReplacesTheFirstNotice() {
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNotNull(awaitNotice())
        settle()

        assertEquals(1, updateNotices().size)
    }

    private companion object {
        const val POLLS = 20
        const val POLL_MS = 100L
    }
}
