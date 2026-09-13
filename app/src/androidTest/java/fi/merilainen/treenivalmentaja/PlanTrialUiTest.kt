package fi.merilainen.treenivalmentaja

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.merilainen.treenivalmentaja.domain.*
import fi.merilainen.treenivalmentaja.data.repository.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlanTrialUiTest {
  @get:Rule val compose = createComposeRule()
  private val run = TrainingSession("s", "p", WorkoutType.RUNNING, 1, "2026-09-13", null, 0,
    runSteps = listOf(RunStep("Alku", durationSec = 60), RunStep("Veto", distanceMeters = 400)))

  @Test fun simulationAndCancelledExportDoNotWrite() {
    var exports = 0
    compose.setContent {
      PlanTrialDialog(PlanPreviewResult.Valid("Testiohjelma", null, listOf(run)), LocalDate.of(2026,9,13), {},
        { _, _ -> exports++; RunExportResult.Success(1,0) })
    }
    compose.onNodeWithText("2026-09-13 · Juoksu", substring = true).performClick()
    compose.onNodeWithText("Seuraava vaihe").performScrollTo().performClick()
    compose.onNodeWithText("Vaihe 2/2").assertExists()
    compose.onNodeWithText("Vie testijuoksu kelloon tänään").performScrollTo().performClick()
    compose.onNodeWithText("Peruuta").performClick()
    assertEquals(0, exports)
    compose.onNodeWithText("Vie testijuoksu kelloon tänään").performScrollTo().performClick()
    compose.onNodeWithText("Vie testi").performClick()
    compose.waitUntil { exports == 1 }
    compose.onNodeWithText("Testi viety. Synkronoi Suunto-sovellus ja kello.").assertExists()
    compose.onNodeWithText("Sulje kokeilu").assertIsDisplayed()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val screenshot = instrumentation.uiAutomation.takeScreenshot()
    java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "trial.png").outputStream().use {
      screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
    screenshot.recycle()
  }

  @Test fun androidResolvesJsonToMainActivity() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://documents/123"), "application/json")
      .addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
    assertEquals(MainActivity::class.java.name, context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name)
  }
}
