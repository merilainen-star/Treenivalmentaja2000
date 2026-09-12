package fi.merilainen.treenivalmentaja

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisOutputLimitException
import fi.merilainen.treenivalmentaja.domain.NextProgramGoal
import fi.merilainen.treenivalmentaja.domain.NextProgramRequest
import fi.merilainen.treenivalmentaja.domain.NextProgramState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextProgramFailureTest {
  @get:Rule val compose = createComposeRule()

  @Test fun exhaustedBudgetExplainsRecoveryWithoutRepeatingTheRequest() {
    val error = AnalysisOutputLimitException()
    var dismissed = 0
    var generated = 0
    var imported = 0
    compose.setContent {
      NextProgramSection(
        state = NextProgramState.Failed(
          error.message.orEmpty(), error.canRetry, NextProgramRequest(NextProgramGoal.BALANCED),
        ),
        onStart = {},
        onGenerate = { generated++ },
        onImport = { imported++ },
        onDismiss = { dismissed++ },
      )
    }
    compose.onNodeWithText(error.message.orEmpty()).assertExists()
    compose.onNodeWithText("Yritä uudelleen").assertDoesNotExist()
    compose.onNodeWithText("Aloita uusi ohjelma").assertDoesNotExist()
    compose.onNodeWithText("Sulje").performClick()
    assertEquals(1, dismissed)
    assertEquals(0, generated)
    assertEquals(0, imported)
  }
}
