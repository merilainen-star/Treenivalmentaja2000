package fi.merilainen.treenivalmentaja

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchRunsUiTest {
  @get:Rule val compose = createComposeRule()
  private val run = TrainingSession("s", "p", WorkoutType.RUNNING, 1, "2026-09-13", null, 0)

  @Test fun editorRequiresValidStagesAndExplicitSave() {
    var saved: List<RunStep>? = null
    compose.setContent { RunStepsEditor(run, {}, { saved = it }) }
    compose.onNodeWithText("Tallenna vaiheet").assertIsNotEnabled()
    compose.onNodeWithText("Vaiheen nimi").performTextInput("Lämmittely")
    compose.onNodeWithText("Kesto sekunteina").performTextInput("600")
    assertNull(saved)
    compose.waitForIdle()
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val screenshot = instrumentation.uiAutomation.takeScreenshot()
    java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "watch-editor.png").outputStream().use {
      screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
    }
    screenshot.recycle()
    compose.onNodeWithText("Tallenna vaiheet").assertIsEnabled().performClick()
    assertEquals(listOf(RunStep("Lämmittely", durationSec = 600)), saved)
  }

  @Test fun cancellingDoesNotSave() {
    var saved = false
    var dismissed = false
    compose.setContent { RunStepsEditor(run, { dismissed = true }, { saved = true }) }
    compose.onNodeWithText("Vaiheen nimi").performTextInput("Veto")
    compose.onNodeWithText("Peruuta").performClick()
    assertTrue(dismissed)
    assertFalse(saved)
  }

  @Test fun missingStepsBlockExport() {
    var exported = false
    compose.setContent { WatchRunsCard(listOf(run), true, false, null, { _, _ -> }, { exported = true }) }
    compose.onNodeWithText("Vie juoksut Intervals.icu:hun").assertIsNotEnabled()
    assertFalse(exported)
  }
}
