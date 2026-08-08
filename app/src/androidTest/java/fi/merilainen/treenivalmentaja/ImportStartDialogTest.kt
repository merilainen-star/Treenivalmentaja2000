package fi.merilainen.treenivalmentaja

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dialog's whole job is to return one boolean, and getting that boolean backwards would put
 * an eight-week plan on the wrong dates while looking perfectly correct on screen. Nothing about
 * the appearance would give it away, so it is asserted here rather than left to a screenshot.
 *
 * This lives in androidTest because `createComposeRule` cannot run under Robolectric in this
 * project: the host activity resolves the launcher icon and Robolectric cannot load the
 * adaptive-icon XML.
 */
@RunWith(AndroidJUnit4::class)
class ImportStartDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var confirmedWith: Boolean? = null
    private var dismissed = false

    private fun show() {
        composeRule.setContent {
            ImportStartDialog(
                onDismiss = { dismissed = true },
                onConfirm = { confirmedWith = it }
            )
        }
    }

    /** The file's own dates are the default, so confirming without touching anything keeps them. */
    @Test
    fun confirmingWithoutChoosingUsesTheFileDates() {
        show()

        composeRule.onNodeWithText("Tuo").performClick()

        assertEquals(false, confirmedWith)
    }

    @Test
    fun choosingStartTodayReportsTrue() {
        show()

        composeRule.onNodeWithText("Alkaa tästä päivästä").performClick()
        composeRule.onNodeWithText("Tuo").performClick()

        assertEquals(true, confirmedWith)
    }

    /** Selecting the second option and changing back must not leave the flag set. */
    @Test
    fun changingBackToTheFileDatesReportsFalse() {
        show()

        composeRule.onNodeWithText("Alkaa tästä päivästä").performClick()
        composeRule.onNodeWithText("Tiedoston päivämäärillä").performClick()
        composeRule.onNodeWithText("Tuo").performClick()

        assertEquals(false, confirmedWith)
    }

    /** Cancelling must import nothing at all, not import with the default. */
    @Test
    fun cancellingImportsNothing() {
        show()

        composeRule.onNodeWithText("Peruuta").performClick()

        assertTrue(dismissed)
        assertNull(confirmedWith)
    }
}
