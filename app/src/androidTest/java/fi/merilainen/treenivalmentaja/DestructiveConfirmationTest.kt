package fi.merilainen.treenivalmentaja

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.data.importer.PendingImport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DestructiveConfirmationTest {
  @get:Rule val compose = createComposeRule()

  @Test fun resetRequiresConfirmationAndCancelPreservesData() {
    var resets = 0
    compose.setContent { ResetSampleDataButton { resets++ } }
    compose.onNodeWithText("Palauta esimerkkidata").performClick()
    assertEquals(0, resets)
    compose.onNodeWithText("Peruuta").performClick()
    assertEquals(0, resets)
    compose.onNodeWithText("Palauta esimerkkidata").performClick()
    compose.onNodeWithText("Poista ja palauta").performClick()
    assertEquals(1, resets)
  }

  @Test fun programmeReplacementExplainsPermanentLossBeforeApproval() {
    var confirmed = 0
    compose.setContent {
      ImportConfirmDialog("Seuraava ohjelma", PendingImport.Replace("Vanha ohjelma", 11),
        onConfirm = { confirmed++ }, onDismiss = {})
    }
    compose.onNodeWithText("11 merkittyä harjoitusta ja niiden historia häviävät pysyvästi.", substring = true)
      .assertExists()
    assertEquals(0, confirmed)
    compose.onNodeWithText("Korvaa", substring = false).performClick()
    assertEquals(1, confirmed)
  }
}
