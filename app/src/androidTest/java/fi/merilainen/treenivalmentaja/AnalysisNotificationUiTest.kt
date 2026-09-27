package fi.merilainen.treenivalmentaja

import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import fi.merilainen.treenivalmentaja.data.notification.AnalysisNotification
import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisNotificationUiTest {
  @get:Rule val permission = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()
  private val id = "notification-ui-test"
  private var originalIntent: android.content.Intent? = null
  @After fun clearNotification() {
    ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java).cancel("analysis:$id", 1)
    // ActivityScenario matches lifecycle callbacks by the original launch URI.
    // MainActivity correctly adopts a new notification intent in onNewIntent.
    compose.runOnUiThread { originalIntent?.let { compose.activity.intent = it } }
  }
  @Test fun notificationOpensItsAnalysisInAnAlreadyRunningApp() {
    compose.runOnUiThread { originalIntent = compose.activity.intent }
    val context = ApplicationProvider.getApplicationContext<Context>()
    NotificationChannels.createChannels(context)
    AnalysisNotification.show(context, id)
    val manager = context.getSystemService(NotificationManager::class.java)
    compose.waitUntil(10_000) { manager.activeNotifications.any { it.tag == "analysis:$id" } }
    val notice = manager.activeNotifications.single { it.tag == "analysis:$id" }.notification
    assertEquals(NotificationChannels.ANALYSES, notice.channelId)
    assertNotNull(notice.contentIntent)
    notice.contentIntent.send()
    compose.waitUntil(15_000) {
      compose.onAllNodesWithText("Harjoituksen AI-analyysi").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Tallennettua analyysiä ei ole saatavilla.").assertIsDisplayed()
  }
}
