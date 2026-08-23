package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app may say that the easy runs have stopped being easy, and — as with the readiness
 * rule — when it may not.
 *
 * The negative cases carry the weight here. This card cannot be acted on, so its only cost is
 * credibility: a finding raised on four runs, or on a single hard Tuesday, teaches the reader to
 * ignore the one that is real.
 */
class EasyRunDriftUseCaseTest {

  private val useCase = EasyRunDriftUseCase()

  private val today = LocalDate.of(2026, 8, 20)

  // ------------------------------------------------------------------ the rule fires

  /** The case it was built for: three easy runs in a row above the athlete's own easy median. */
  @Test
  fun `three easy runs above the median raise the finding`() {
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities = listOf(70, 71, 72, 80, 81, 82)) + easyToday(),
        runMetricsBySession = metrics(listOf(70, 71, 72, 80, 81, 82)),
      )

    val finding = drift as EasyRunDrift.Finding
    assertEquals("tanaan", finding.sessionId)
    assertEquals(WorkoutType.RUNNING, finding.type)
    // Most recent first, which is the order the card reads them in.
    assertEquals(listOf(82, 81, 80), finding.recentIntensityPercent)
    // Six values, so the median is the midpoint of 72 and 80.
    assertEquals(76, finding.medianIntensityPercent)
    assertEquals(6, finding.comparableSessions)
  }

  /** Six is the floor, not the population: a longer history is compared in full. */
  @Test
  fun `a longer history is used whole`() {
    val intensities = listOf(60, 62, 64, 66, 68, 70, 72, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    val finding = drift as EasyRunDrift.Finding
    assertEquals(10, finding.comparableSessions)
    assertEquals(69, finding.medianIntensityPercent)
  }

  /** The median is rounded for the card; the comparison is not. */
  @Test
  fun `the comparison is made before the rounding`() {
    // Median of these six is the midpoint of 74 and 75, i.e. 74,5 — and 75 is above it. Rounded
    // first, the median would be 74 or 75 depending on the direction and the answer would change.
    val intensities = listOf(70, 71, 74, 75, 76, 77)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    val finding = drift as EasyRunDrift.Finding
    assertEquals(listOf(77, 76, 75), finding.recentIntensityPercent)
    assertEquals(75, finding.medianIntensityPercent)
  }

  // ------------------------------------------------------------------ the rule stays quiet

  /**
   * The most important negative case. One hard easy run is a Tuesday, a hill, a headwind — three
   * in a row is what makes it a finding.
   */
  @Test
  fun `two hard runs and one ordinary one say nothing`() {
    val intensities = listOf(70, 71, 72, 81, 70, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Five comparable runs is a median with an opinion about two of them. Silence instead. */
  @Test
  fun `fewer than six comparable runs produce silence`() {
    val intensities = listOf(70, 71, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /**
   * An activity synced before schema v9 carries no intensity. Missing is not zero, and it is not
   * easy either — the run is excluded from the comparison rather than counted as calm.
   */
  @Test
  fun `runs without an intensity are excluded rather than counted`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val withoutIntensity =
      metrics(intensities).mapValues { (id, metric) ->
        if (id == "run-0" || id == "run-1") metric.copy(intensity = null) else metric
      }

    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = withoutIntensity,
      )

    // Four comparable runs left, which is below the floor — and crucially not six runs of which
    // two were read as 0 %.
    assertEquals(EasyRunDrift.None, drift)
  }

  /** A session with no matched activity was not measured, so it is not evidence. */
  @Test
  fun `completed sessions with no matched activity are not counted`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities).filterKeys { it != "run-0" },
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Nothing easy is scheduled today, so there is no morning to say it on. */
  @Test
  fun `a day with no easy session says nothing`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Today's session is hard, and how the easy ones went is a different question. */
  @Test
  fun `a hard session today is not this rule's business`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday().map { it.copy(intensity = Intensity.HARD) },
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** A session with no planned intensity at all states nothing to compare against. */
  @Test
  fun `a session without a planned intensity says nothing`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday().map { it.copy(intensity = null) },
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** The run is already done. This is a word before a session, not a verdict on one. */
  @Test
  fun `a session already completed today gets no card`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val drift =
      useCase.execute(
        today = today,
        sessions =
          history(intensities) + easyToday().map { it.copy(status = SessionStatus.COMPLETED) },
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Strength sessions are not a running baseline, and a mixed history is not a comparison. */
  @Test
  fun `sessions of another type are not comparable`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val strengthHistory =
      history(intensities).map { it.copy(type = WorkoutType.STRENGTH) }

    val drift =
      useCase.execute(
        today = today,
        sessions = strengthHistory + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Hard sessions are not an easy baseline either, however many of them there are. */
  @Test
  fun `sessions of another planned intensity are not comparable`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val hardHistory = history(intensities).map { it.copy(intensity = Intensity.MODERATE) }

    val drift =
      useCase.execute(
        today = today,
        sessions = hardHistory + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** A session that was planned and never done measures nothing. */
  @Test
  fun `open and skipped sessions are not part of the history`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val partlyOpen =
      history(intensities).mapIndexed { index, session ->
        if (index < 2) session.copy(status = SessionStatus.SKIPPED) else session
      }

    val drift =
      useCase.execute(
        today = today,
        sessions = partlyOpen + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Every comparable run identical: nothing is above the median, so nothing drifted. */
  @Test
  fun `a flat history raises nothing`() {
    val intensities = List(8) { 72 }
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Equal to the median is not above it — the boundary belongs to silence. */
  @Test
  fun `a run exactly at the median does not count as drift`() {
    // Median of these seven is 74, and the newest run sits exactly on it.
    val intensities = listOf(70, 71, 72, 74, 80, 81, 74)
    val drift =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday(),
        runMetricsBySession = metrics(intensities),
      )

    assertEquals(EasyRunDrift.None, drift)
  }

  /** Which three are "the last three" is decided by when they were run, not by list order. */
  @Test
  fun `the three most recent are chosen by time, not by input order`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val shuffled = (history(intensities) + easyToday()).reversed()

    val finding =
      useCase.execute(today, shuffled, metrics(intensities)) as EasyRunDrift.Finding

    assertEquals(listOf(82, 81, 80), finding.recentIntensityPercent)
  }

  /** Nothing stored at all is the ordinary state of a fresh install. */
  @Test
  fun `no history at all says nothing`() {
    assertEquals(EasyRunDrift.None, useCase.execute(today, easyToday(), emptyMap()))
  }

  /** Two easy sessions today: the earlier one is the one the card sits next to. */
  @Test
  fun `the earliest easy session of the day carries the card`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val second =
      easyToday().first().copy(id = "iltapaiva", scheduledTime = "17:00", remindAtUtc = epoch(today) + 61_200_000L)

    val finding =
      useCase.execute(
        today = today,
        sessions = history(intensities) + easyToday() + second,
        runMetricsBySession = metrics(intensities),
      ) as EasyRunDrift.Finding

    assertEquals("tanaan", finding.sessionId)
  }

  /** The finding never claims to be an offer: there is nothing here to accept. */
  @Test
  fun `the finding carries only what the card asserts`() {
    val intensities = listOf(70, 71, 72, 80, 81, 82)
    val finding =
      useCase.execute(today, history(intensities) + easyToday(), metrics(intensities))
        as EasyRunDrift.Finding

    assertTrue(finding.recentIntensityPercent.size == 3)
    assertTrue(finding.comparableSessions >= finding.recentIntensityPercent.size)
  }

  // ------------------------------------------------------------------ helpers

  /**
   * Completed easy runs, oldest first, one per day ending yesterday. `intensities` is read in the
   * same order, so the last entry is the most recent run.
   */
  private fun history(intensities: List<Int>): List<TrainingSession> =
    intensities.mapIndexed { index, _ ->
      val date = today.minusDays((intensities.size - index).toLong())
      TrainingSession(
        id = "run-$index",
        planId = "plan",
        type = WorkoutType.RUNNING,
        weekNumber = 1,
        scheduledDate = date.toString(),
        scheduledTime = "08:00",
        remindAtUtc = epoch(date),
        intensity = Intensity.EASY,
        status = SessionStatus.COMPLETED,
      )
    }

  /** What the watch recorded for each of [history]'s runs, keyed the same way. */
  private fun metrics(intensities: List<Int>): Map<String, CompletedRunMetrics> =
    intensities
      .mapIndexed { index, intensity ->
        "run-$index" to
          CompletedRunMetrics(
            activityId = "activity-$index",
            sportType = "Run",
            startTimeUtc = epoch(today.minusDays((intensities.size - index).toLong())),
            movingTimeSec = 3_000,
            intensity = intensity.toDouble(),
          )
      }
      .toMap()

  /** Today's easy run, still ahead. A single-element list so callers can `map` over it. */
  private fun easyToday(): List<TrainingSession> =
    listOf(
      TrainingSession(
        id = "tanaan",
        planId = "plan",
        type = WorkoutType.RUNNING,
        weekNumber = 2,
        scheduledDate = today.toString(),
        scheduledTime = "08:00",
        remindAtUtc = epoch(today),
        intensity = Intensity.EASY,
        status = SessionStatus.PLANNED,
      )
    )

  private fun epoch(date: LocalDate): Long = date.toEpochDay() * 86_400_000L
}
