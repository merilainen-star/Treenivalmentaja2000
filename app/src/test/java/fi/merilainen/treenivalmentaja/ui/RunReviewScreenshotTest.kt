package fi.merilainen.treenivalmentaja.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.*
import fi.merilainen.treenivalmentaja.*
import fi.merilainen.treenivalmentaja.domain.*
import fi.merilainen.treenivalmentaja.ui.theme.MyApplicationTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class RunReviewScreenshotTest {
  // The committed baselines are recorded on Windows while CI runs on Linux. Match the other
  // screenshot suites' tolerance so sub-pixel font rasterization does not block APK publishing.
  private val options = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f),
  )

  private val steps = buildList {
    add(RunStep("Lämmittely - 12 min kevyttä juoksua", durationSec = 720))
    repeat(3) { add(RunStep("Kiihdytys ${it + 1}/3 - rento, ei sprinttiä", durationSec = 15))
      if (it < 2) add(RunStep("Palautus", durationSec = 45)) }
    repeat(6) { add(RunStep("Veto ${it + 1}/6", distanceMeters = 400, paceSecPerKm = 265))
      if (it < 5) add(RunStep("Palautus", durationSec = 90)) }
    add(RunStep("Loppuverryttely", durationSec = 600))
  }
  private fun trace(): RunTrace {
    val planned = plannedTimeline(steps)
    val windows = planned.stages.map { LapWindow(it.index + 1, it.start.toLong(), it.end.toLong()) }
    var syntheticHr = 95.0
    return RunTrace((0..planned.extent.toInt() step 5).map { second ->
      val stage = planned.stages.firstOrNull { second >= it.start && second < it.end }
      val target = when (stage?.phase) { RunPhase.WORK -> 161; RunPhase.STRIDE -> 151; else -> 118 }
      syntheticHr += (target - syntheticHr) * .18
      HeartRatePoint(second.toLong(), if (second in 1100..1130) null else syntheticHr.toInt() + (3 * kotlin.math.sin(second / 27.0)).toInt())
    }, windows, speed = (0..planned.extent.toInt() step 5).map { second ->
      val stage = planned.stages.firstOrNull { second >= it.start && second < it.end }
      val speed = when (stage?.phase) { RunPhase.WORK -> 3.78; RunPhase.STRIDE -> 4.5; RunPhase.RECOVERY -> 2.0; else -> 2.8 }
      SpeedPoint(second.toLong(), if (second in 1100..1130) null else speed + .12 * kotlin.math.sin(second / 9.0))
    })
  }
  private fun capture(dark: Boolean, done: Boolean) = stillScreenshot {
    val workout = Workout("run", 0, WorkoutType.RUNNING, "12:00", 45,
      "Hallittu vetoharjoitus. Juokse vedot tasaisesti ja kiihdytykset rennosti.",
      status = if (done) SessionStatus.COMPLETED else SessionStatus.PLANNED, runSteps = steps)
    captureRoboImage(
      "src/test/screenshots/run_review_${if (dark) "dark" else "light"}_${if (done) "after" else "before"}.png",
      roborazziOptions = options,
    ) {
      MyApplicationTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) {
        TodayScreenContent(listOf(workout),
          recovery = DailyRecovery(date = "2026-09-27", readiness = 82, sleep = 76),
          runMetrics = if (done) mapOf("run" to CompletedRunMetrics("watch", "Run", 0, 2912,
            distanceKm = 8.2, avgHeartRate = 148, trace = trace())) else emptyMap(),
          analyses = if (done) mapOf("run" to AiAnalysisState.Loaded("Kokonaisuus pysyi hallittuna. Vetokohtaista toteumaa ei voi vahvistaa kilometriväliajoista.", "Esimerkkipyyntö")) else emptyMap(),
          analysisConfigured = true)
      } }
    }
  }
  @Test fun lightBefore() = capture(false, false)
  @Test fun lightAfter() = capture(false, true)
  @Test fun darkBefore() = capture(true, false)
  @Test fun darkAfter() = capture(true, true)
  private fun chart(dark: Boolean) = stillScreenshot {
    captureRoboImage(
      "src/test/screenshots/run_timeline_${if (dark) "dark" else "light"}.png",
      roborazziOptions = options,
    ) {
      MyApplicationTheme(darkTheme = dark) { Surface {
        RunProfileCard(steps, title = "Juoksun vaiheet · esimerkkidata", recording = trace(), completed = true, showPrescription = false)
      } }
    }
  }
  @Test fun lightTimeline() = chart(false)
  @Test fun darkTimeline() = chart(true)
}
