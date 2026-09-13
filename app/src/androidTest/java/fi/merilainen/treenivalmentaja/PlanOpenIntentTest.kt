package fi.merilainen.treenivalmentaja

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanOpenIntentTest {
  @get:Rule val compose = createEmptyComposeRule()

  @Test fun coldOpenAndNewIntentBothOfferTrial() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = context.contentResolver
    fun document(name: String): android.net.Uri {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "trial-${System.nanoTime()}.json")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
      }
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
      resolver.openOutputStream(uri)!!.use { it.write("""{"schemaVersion":1,"plan":{"id":"intent-test","name":"$name","startDate":"2026-09-13","timeZone":"Europe/Helsinki"},"weeks":[{"weekNumber":1,"sessions":[{"id":"run","type":"RUNNING","date":"2026-09-13","runSteps":[{"name":"Kevyt","durationSec":60}]}]}]}""".toByteArray()) }
      return uri
    }
    val first = document("Ensimmäinen tiedosto")
    val second = document("Toinen tiedosto")
    fun intent(uri: android.net.Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/json")
      .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val launchIntent = intent(first)
    try {
      ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
        try {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Ensimmäinen tiedosto").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kokeile").assertIsEnabled()
        compose.onNodeWithText("Sulje").performClick()
        context.startActivity(intent(second))
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Toinen tiedosto").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kokeile").performClick()
        compose.onNodeWithText("Ohjelman kokeilu").assertExists()
        compose.onNodeWithText("Sulje kokeilu").assertIsDisplayed()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "trial-open.png").outputStream().use { stream ->
          screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        screenshot.recycle()
        compose.onNodeWithText("Sulje kokeilu").performClick()
        scenario.onActivity { org.junit.Assert.assertEquals(second, it.intent.data) }
        } finally {
          // ActivityScenario filters lifecycle callbacks by the original intent's URI.
          // Production correctly calls setIntent on new delivery; restore only for test teardown.
          scenario.onActivity { it.intent = launchIntent }
        }
      }
    } finally { resolver.delete(first, null, null); resolver.delete(second, null, null) }
  }
}
