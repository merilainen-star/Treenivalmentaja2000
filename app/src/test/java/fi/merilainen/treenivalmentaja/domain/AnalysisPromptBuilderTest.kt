package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What actually reaches the model.
 *
 * The point of testing a prompt builder is not the wording — that will change — but the two
 * properties the wording must never lose: **a missing measurement is omitted rather than zeroed**,
 * and **the guardrails are always present**. Both are things a reader would not notice going wrong,
 * because a prompt with a fabricated zero in it looks perfectly normal.
 */
class AnalysisPromptBuilderTest {

  private val builder = AnalysisPromptBuilder()

  private val day = LocalDate.of(2026, 8, 17)

  // ------------------------------------------------------------------ missing is not zero

  /**
   * The rule the whole Oura layer is built on, applied to the prompt.
   *
   * A night the ring was not worn must not reach the model as "HRV 0 ms", which reads as a
   * catastrophic reading rather than as no reading — and would be acted on as one.
   */
  @Test
  fun `omits a night with no measurements rather than sending zeroes`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay = mapOf(day to DailyRecovery(date = day.toString())),
        )
      )

    assertFalse(prompt.contains("HRV"))
    assertFalse(prompt.contains("leposyke"))
    assertFalse(prompt.contains("0 ms"))
  }

  /** A day the app never fetched is absent from the map, and absent from the prompt. */
  @Test
  fun `omits days that were never fetched`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay = emptyMap(),
        )
      )

    assertFalse(prompt.contains("Palautuminen"))
  }

  @Test
  fun `includes the nightly measurements when they exist`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay =
            mapOf(
              day to
                DailyRecovery(
                  date = day.toString(),
                  readiness = 68,
                  sleep = 74,
                  averageHrvMs = 61,
                  restingHeartRate = 48,
                )
            ),
        )
      )

    assertTrue(prompt.contains("HRV 61 ms"))
    assertTrue(prompt.contains("leposyke 48"))
    assertTrue(prompt.contains("palautuminen 68"))
  }

  /** Half a reading is still a reading: the fields that exist are sent, the rest are not. */
  @Test
  fun `sends only the measurements a partial night actually has`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay =
            mapOf(day to DailyRecovery(date = day.toString(), averageHrvMs = 55)),
        )
      )

    assertTrue(prompt.contains("HRV 55 ms"))
    assertFalse(prompt.contains("leposyke"))
    assertFalse(prompt.contains("palautuminen "))
  }

  // ------------------------------------------------------------------ what each prompt carries

  @Test
  fun `a completed prompt carries what was planned and what happened`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          plannedDurationMin = 45,
          plannedIntensity = Intensity.EASY,
          description = "Peruskestävyys",
          run =
            CompletedRunMetrics(
              activityId = "i1",
              sportType = "Run",
              startTimeUtc = 0L,
              movingTimeSec = 2700,
              distanceKm = 8.0,
              avgSpeedMps = 3.0,
              trainingLoad = 62,
            ),
        )
      )

    assertTrue(prompt.contains("45 min"))
    assertTrue(prompt.contains(Intensity.EASY.title))
    assertTrue(prompt.contains("Peruskestävyys"))
    assertTrue(prompt.contains("kuormitus 62"))
  }

  /** The load pair is written only when both halves exist — one alone invites inferring the other. */
  @Test
  fun `an upcoming prompt omits the load section when only one half is known`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          acuteLoad = 42.0,
          chronicLoad = null,
        )
      )

    assertFalse(prompt.contains("Kuormitus"))
  }

  @Test
  fun `an upcoming prompt carries both loads and their difference`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          acuteLoad = 60.0,
          chronicLoad = 50.0,
        )
      )

    assertTrue(prompt.contains("Akuutti kuormitus"))
    assertTrue(prompt.contains("Krooninen kuormitus"))
    // Fitness minus fatigue: negative here, which is the interesting direction.
    assertTrue(prompt.contains("-10"))
  }

  // ------------------------------------------------------------------ guardrails

  /**
   * ADR-005 in one assertion: the app cannot act on a proposed plan edit, so an answer that read
   * like one would be offering something that does not exist.
   */
  @Test
  fun `both prompts forbid proposing plan changes`() {
    val completed =
      builder.completed(CompletedAnalysisInput(type = WorkoutType.RUNNING, date = day))
    val upcoming = builder.upcoming(UpcomingAnalysisInput(type = WorkoutType.RUNNING, date = day))

    assertTrue(completed.contains("Älä ehdota muutoksia harjoitusohjelmaan"))
    assertTrue(upcoming.contains("Älä ehdota muutoksia harjoitusohjelmaan"))
  }

  @Test
  fun `both prompts forbid inventing numbers`() {
    val completed =
      builder.completed(CompletedAnalysisInput(type = WorkoutType.RUNNING, date = day))
    val upcoming = builder.upcoming(UpcomingAnalysisInput(type = WorkoutType.RUNNING, date = day))

    assertTrue(completed.contains("Älä keksi lukuja"))
    assertTrue(upcoming.contains("Älä keksi lukuja"))
  }

  /** Pure: the same input twice is the same string, so the "Näytä pyyntö" panel cannot drift. */
  @Test
  fun `is deterministic`() {
    val input = CompletedAnalysisInput(type = WorkoutType.STRENGTH, date = day)

    assertTrue(builder.completed(input) == builder.completed(input))
  }
}
