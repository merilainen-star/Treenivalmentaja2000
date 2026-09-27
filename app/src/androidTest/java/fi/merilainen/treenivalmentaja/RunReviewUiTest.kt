package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.domain.*
import fi.merilainen.treenivalmentaja.ui.theme.MyApplicationTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunReviewUiTest {
  @Test fun todayHeaderShowsOuraActivityAsThirdScore() {
    compose.setContent { MyApplicationTheme { Surface {
      TodayScreenContent(listOf(Workout("run", 0, WorkoutType.RUNNING, "12:00", 30, "Ohje")),
        recovery = DailyRecovery("2026-09-27", readiness = 85, sleep = 86, activity = 98))
    } } }
    compose.onNodeWithText("Palautuminen 85 · Uni 86 · Aktiivisuus 98").assertExists()
    compose.onNodeWithText("Päivän vointi", substring = true).assertDoesNotExist()
  }

  @Test fun todayHeaderDoesNotInventMissingActivityScore() {
    compose.setContent { MyApplicationTheme { Surface {
      TodayScreenContent(listOf(Workout("run", 0, WorkoutType.RUNNING, "12:00", 30, "Ohje")),
        recovery = DailyRecovery("2026-09-27", readiness = 85, sleep = 86))
    } } }
    compose.onNodeWithText("Palautuminen 85 · Uni 86 · Aktiivisuus —").assertExists()
  }

  @Test fun missingLapBoundariesNeverBecomeGuessedPhaseBars() {
    val trace = RunTrace(listOf(HeartRatePoint(0, 100), HeartRatePoint(10, 130)))
    compose.setContent { MyApplicationTheme { Surface {
      RunProfileCard(listOf(RunStep("Veto", durationSec = 30)), recording = trace, completed = true)
    } } }
    compose.onNodeWithText("Kellon vaiherajat puuttuvat.").assertExists()
    compose.onNodeWithText("Tutki vaiheita").assertDoesNotExist()
  }
  @Test fun proportionalBarsUseTimeForTouchAndKeepShortStagesAccessible() {
    val timed = listOf(RunStep("Lämmittely", durationSec = 720), RunStep("Kiihdytys", durationSec = 12))
    compose.setContent { MyApplicationTheme { Surface { RunProfileCard(timed) } } }
    compose.onNodeWithContentDescription("Leveys = kesto.", substring = true).performTouchInput {
      click(androidx.compose.ui.geometry.Offset(center.x * 1.8f, center.y))
    }
    compose.onNodeWithText("1/2 · Lämmittely · 720 s").assertExists()
    compose.onNodeWithText("Seuraava vaihe").performClick()
    compose.onNodeWithText("2/2 · Kiihdytys · 12 s").assertExists()
  }
  @Test fun completedChartShowsMeasuredCurveAndPhaseHeartRate() {
    val timed = listOf(RunStep("Lämmittely", durationSec = 720), RunStep("Kiihdytys", durationSec = 12))
    val trace = RunTrace(listOf(HeartRatePoint(0, 100), HeartRatePoint(10, 130), HeartRatePoint(720, 160)),
      listOf(LapWindow(1, 0, 720), LapWindow(2, 720, 732)))
    compose.setContent { MyApplicationTheme { Surface { RunProfileCard(timed, recording = trace, completed = true) } } }
    compose.onNodeWithContentDescription("Mitattu syke 100–160", substring = true).assertExists()
    compose.onNodeWithText("Tutki vaiheita").performClick()
    compose.onNodeWithText("Kesto 720 s · syke 100–130 /min").assertExists()
    compose.onNodeWithText("Seuraava vaihe").performClick()
    compose.onNodeWithText("Kesto 12 s · syke 160–160 /min").assertExists()
  }
  @get:Rule val compose = createComposeRule()
  @Test fun speedOverlayTogglesAndSelectedLapAverageReachCompletedCard() {
    val timed = listOf(RunStep("Reipas", durationSec = 360, paceSecPerKm = 305))
    val trace = RunTrace(listOf(HeartRatePoint(0, 130), HeartRatePoint(10, 150)),
      listOf(LapWindow(1, 0, 360)), speed = listOf(SpeedPoint(0, 3.0), SpeedPoint(10, 4.0)))
    val run = CompletedRunMetrics("watch", "Run", 0, 360, trace = trace, laps = listOf(RunLap(1, 360_000, 1200.0)))
    val workout = Workout("run", 0, WorkoutType.RUNNING, "12:00", 6, "Ohje", status = SessionStatus.COMPLETED, runSteps = timed)
    compose.setContent { MyApplicationTheme { Surface {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { WorkoutCardToday(workout, {}, {}, run = run) }
    } } }
    compose.onNodeWithContentDescription("Mitattu vauhtikäyrä", substring = true).assertExists()
    compose.onNodeWithText("Tutki vaiheita").performScrollTo().performClick()
    compose.onNodeWithText("Toteutunut keskivauhti 5:00 /km").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Vauhti").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Mitattu vauhtikäyrä", substring = true).assertDoesNotExist()
    compose.onNodeWithContentDescription("Mitattu syke", substring = true).assertExists()
    compose.onNodeWithText("Syke").performClick()
    compose.onNodeWithContentDescription("Mitattu syke", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Vaiheet").performClick()
    compose.onNodeWithContentDescription("Vaihepalkit näkyvissä", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Toteutunut keskivauhti 5:00 /km").assertExists()
    compose.onNodeWithText("Vauhti").performClick()
    compose.onNodeWithContentDescription("Mitattu vauhtikäyrä", substring = true).assertExists()
    compose.onNodeWithText("Vaiheiden nimet kohdistettu", substring = true).assertDoesNotExist()
  }
  private val steps = listOf(RunStep("Lämmittely", durationSec = 600),
    RunStep("Veto 1/2", distanceMeters = 400), RunStep("Palautus", durationSec = 90),
    RunStep("Veto 2/2", distanceMeters = 400), RunStep("Loppuverryttely", durationSec = 600))
  @Test fun allStagesRemainSelectableWithoutShrinkingText() {
    compose.setContent { MyApplicationTheme { Surface { RunProfileCard(steps) } } }
    compose.onNodeWithText("Tutki vaiheita").performClick()
    compose.onNodeWithText("1/5 · Lämmittely · 600 s").assertExists()
    repeat(4) { compose.onNodeWithText("Seuraava vaihe").performClick() }
    compose.onNodeWithText("5/5 · Loppuverryttely · 600 s").assertExists()
    compose.onNodeWithText("Seuraava vaihe").assertIsNotEnabled()
    compose.onNodeWithText("Veto · 2 × 400 m", substring = true).assertExists()
  }
  @Test fun completedRunKeepsPlanReadableAndPromotesAnalysis() {
    var undone = false
    val workout = Workout("run", 0, WorkoutType.RUNNING, "12:00", 30, "Pitkä alkuperäinen ohje",
      status = SessionStatus.COMPLETED, appliedLighterVariant = true, runSteps = steps)
    compose.setContent { MyApplicationTheme(darkTheme = true) { Surface {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        WorkoutCardToday(workout, {}, {}, analysis = AiAnalysisState.Loaded("Analyysin ensimmäinen havainto", "Tarkka pyyntö"),
          analysisConfigured = true, autoCompleted = true, onUndoAutoCompletion = { undone = true })
      }
    } } }
    compose.onNodeWithText("AI-analyysi valmis").assertExists()
    compose.onNodeWithText("Kevennetty versio käytössä.").assertExists()
    compose.onNodeWithText("Lue analyysi").performScrollTo().performClick()
    compose.onNodeWithText("Tiivistä analyysi").assertExists()
    compose.onNodeWithText("Kaikki ohjeet ja mittarit").performScrollTo().performClick()
    compose.onNodeWithText("Pitkä alkuperäinen ohje").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Merkitty automaattisesti · Peru merkintä").performScrollTo().performClick()
    assertTrue(undone)
  }
  @Test fun lighterPlanAndDistanceOnlyNextRunKeepHonestLabels() {
    val workout = Workout("run", 0, WorkoutType.RUNNING, "12:00", 30, "Ohje",
      appliedLighterVariant = true, runSteps = steps)
    compose.setContent { MyApplicationTheme { Surface {
      TodayScreenContent(listOf(workout, workout.copy(id = "next", dayOffset = 1, durationMin = 0)))
    } } }
    compose.onNodeWithText("Kevennetty versio käytössä.").assertExists()
    compose.onNodeWithText("Juoksu · 1 päivän päästä").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Juoksu · 0 min · 1 päivän päästä").assertDoesNotExist()
  }

  @Test fun completedCardExpandsMeasuredWatchLaps() {
    val workout = Workout("run", 0, WorkoutType.RUNNING, "12:00", 30, "Ohje",
      status = SessionStatus.COMPLETED, runSteps = steps)
    val run = CompletedRunMetrics("watch", "Run", 0, 1800,
      laps = listOf(RunLap(1, 720_000, 2000.0, 130), RunLap(2, 360_000, 1200.0, 150)))
    compose.setContent { MyApplicationTheme { Surface {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        WorkoutCardToday(workout, {}, {}, run = run)
      }
    } } }
    compose.onNodeWithText("Kierrokset (2)", substring = true).performScrollTo().performClick()
    compose.onNodeWithText("2. 6:00,0 · 1200 m · 5:00 /km · syke 150").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Kierrokset (2)", substring = true).performClick()
    compose.onNodeWithText("2. 6:00,0 · 1200 m · 5:00 /km · syke 150").assertDoesNotExist()
  }
}
