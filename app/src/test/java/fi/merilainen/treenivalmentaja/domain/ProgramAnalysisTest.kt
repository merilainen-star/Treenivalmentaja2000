package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the model is not allowed to do.
 *
 * Every figure in a programme report comes from here, so the cases that matter are the ones where a
 * plausible-looking number would be a lie: a completion rate that counts a rescheduled session as
 * missed, a trend computed from one run against one run, a comparison between an easy run and an
 * interval session.
 */
class ProgramAnalysisTest {

  private val today = LocalDate.of(2026, 9, 6)

  private fun session(
    id: String,
    date: String,
    week: Int,
    type: WorkoutType = WorkoutType.RUNNING,
    status: SessionStatus = SessionStatus.COMPLETED,
    intensity: Intensity? = Intensity.EASY,
    distanceKm: Double? = null,
    durationMin: Int? = null,
    exercises: List<Exercise>? = null,
    lighter: Boolean = false,
  ) =
    TrainingSession(
      id = id,
      planId = "p1",
      type = type,
      weekNumber = week,
      scheduledDate = date,
      scheduledTime = null,
      remindAtUtc = 0,
      durationMin = durationMin,
      distanceKm = distanceKm,
      intensity = intensity,
      exercises = exercises,
      status = status,
      appliedLighterVariant = lighter,
    )

  private fun run(
    distanceKm: Double,
    durationSec: Long,
    avgHr: Int? = null,
    load: Int? = null,
  ): CompletedRunMetrics =
    CompletedRunMetrics(
      activityId = "a-$distanceKm-$durationSec",
      sportType = "Run",
      startTimeUtc = 0,
      movingTimeSec = durationSec,
      distanceKm = distanceKm,
      avgSpeedMps = distanceKm * 1000.0 / durationSec,
      avgHeartRate = avgHr,
      trainingLoad = load,
    )

  private fun analyse(
    records: List<ProgramSessionRecord>,
    recovery: Map<LocalDate, DailyRecovery> = emptyMap(),
    goals: String? = null,
  ) =
    buildProgramAnalysis(
      planName = "Testiohjelma",
      planDescription = goals,
      sessions = records,
      recoveryByDay = recovery,
      today = today,
    )

  // ------------------------------------------------------------------ shape and identity

  /** No sessions is no programme. A report about nothing would be a report about the default. */
  @Test
  fun `an empty plan produces no analysis`() {
    assertNull(analyse(emptyList()))
  }

  @Test
  fun `the programme spans its first and last session`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s2", "2026-08-20", week = 3)),
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
        )
      )!!

    assertEquals(LocalDate.of(2026, 8, 1), analysis.identity.startDate)
    assertEquals(LocalDate.of(2026, 8, 20), analysis.identity.endDate)
    assertEquals(3, analysis.identity.weekCount)
  }

  /** The plan author's own words, not a paraphrase — they are what everything else is judged against. */
  @Test
  fun `the plan's description is carried through as its goals`() {
    val analysis =
      analyse(
        listOf(ProgramSessionRecord(session("s1", "2026-08-01", week = 1))),
        goals = "  Peruskestävyyttä ja 10 km alle 55 min  ",
      )!!

    assertEquals("Peruskestävyyttä ja 10 km alle 55 min", analysis.identity.goals)
  }

  /** A plan with nothing open is over, whatever the calendar says. */
  @Test
  fun `a plan with no open sessions is finished`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(session("s2", "2026-08-05", week = 1, status = SessionStatus.SKIPPED)),
        )
      )!!

    assertEquals(ProgramPhase.Finished, analysis.phase)
    assertNull(analysis.remaining)
  }

  /**
   * The week comes off the plan's own numbering, not from dividing days by seven — the two disagree
   * the moment a plan starts mid-week, and the plan's number is the one the person sees.
   */
  @Test
  fun `an unfinished plan reports the week it is in`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-31", week = 5)),
          ProgramSessionRecord(session("s2", "2026-09-04", week = 6)),
          ProgramSessionRecord(session("s3", "2026-09-12", week = 7, status = SessionStatus.PLANNED)),
        )
      )!!

    assertEquals(ProgramPhase.InProgress(week = 6, ofWeeks = 7), analysis.phase)
  }

  // ------------------------------------------------------------------ adherence

  /**
   * The rule the completion rate lives or dies by. A rescheduled session did not go missing — it has
   * a successor row that is counted in its place — and a cancelled one was removed from the plan
   * rather than skipped. Counting either as a failure would make every replanned week look abandoned.
   */
  @Test
  fun `rescheduled and cancelled sessions are outside the completion rate`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(session("s2", "2026-08-03", week = 1)),
          ProgramSessionRecord(session("s3", "2026-08-05", week = 1, status = SessionStatus.SKIPPED)),
          ProgramSessionRecord(session("s4", "2026-08-07", week = 1, status = SessionStatus.RESCHEDULED)),
          ProgramSessionRecord(session("s5", "2026-08-09", week = 1, status = SessionStatus.CANCELLED)),
        )
      )!!

    // Three decided: two done, one skipped.
    assertEquals(3, analysis.adherence.decided)
    assertEquals(67, analysis.adherence.completionRate)
    assertEquals(1, analysis.adherence.rescheduled)
    assertEquals(1, analysis.adherence.cancelled)
  }

  /** Nothing decided yet is not nought per cent — that would read as total failure on day one. */
  @Test
  fun `a plan that has not started has no completion rate`() {
    val analysis =
      analyse(
        listOf(ProgramSessionRecord(session("s1", "2026-09-20", week = 1, status = SessionStatus.PLANNED)))
      )!!

    assertNull(analysis.adherence.completionRate)
  }

  @Test
  fun `adherence is split by workout type`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(session("s2", "2026-08-03", week = 1, type = WorkoutType.STRENGTH)),
          ProgramSessionRecord(
            session("s3", "2026-08-05", week = 1, type = WorkoutType.STRENGTH, status = SessionStatus.SKIPPED)
          ),
        )
      )!!

    assertEquals(1, analysis.adherence.byType[WorkoutType.RUNNING]?.completed)
    assertEquals(1, analysis.adherence.byType[WorkoutType.STRENGTH]?.completed)
    assertEquals(1, analysis.adherence.byType[WorkoutType.STRENGTH]?.skipped)
  }

  // ------------------------------------------------------------------ weeks

  @Test
  fun `a week sums what was actually done, not what was planned`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1), run = run(8.0, 2700)),
          ProgramSessionRecord(session("s2", "2026-08-03", week = 1), run = run(5.0, 1800)),
          ProgramSessionRecord(
            session("s3", "2026-08-05", week = 1, status = SessionStatus.SKIPPED),
            run = run(99.0, 9999),
          ),
        )
      )!!

    val week = analysis.weeks.single()
    assertEquals(3, week.planned)
    assertEquals(2, week.completed)
    assertEquals(13.0, week.runKm!!, 0.001)
  }

  // ------------------------------------------------------------------ runs and their trends

  /** An easy run and an interval session share a unit and nothing else. */
  @Test
  fun `runs are compared only within the same planned intensity`() {
    val records =
      (1..4).map {
        ProgramSessionRecord(
          session("e$it", "2026-08-0$it", week = 1, intensity = Intensity.EASY),
          run = run(8.0, 2880, avgHr = 140),
        )
      } +
        (1..4).map {
          ProgramSessionRecord(
            session("h$it", "2026-08-1$it", week = 2, intensity = Intensity.HARD),
            run = run(6.0, 1620, avgHr = 172),
          )
        }
    val analysis = analyse(records)!!

    assertEquals(2, analysis.runTrends.size)
    assertEquals(setOf(Intensity.EASY, Intensity.HARD), analysis.runTrends.map { it.intensity }.toSet())
  }

  /**
   * The finding the whole comparison exists for: the same pace at a lower heart rate. Four easy
   * runs, identical distance and duration throughout, heart rate falling from 150 to 140.
   */
  @Test
  fun `the same pace at a lower heart rate shows as a heart rate delta and no pace delta`() {
    val hrs = listOf(152, 148, 142, 138)
    val analysis =
      analyse(
        hrs.mapIndexed { index, hr ->
          ProgramSessionRecord(
            session("s$index", "2026-08-0${index + 1}", week = 1),
            run = run(8.0, 2880, avgHr = hr),
          )
        }
      )!!

    val trend = analysis.runTrends.single()
    assertEquals(2, trend.earlyCount)
    assertEquals(2, trend.lateCount)
    assertEquals(0, trend.paceDeltaSec)
    assertEquals(-10, trend.heartRateDelta)
  }

  /** Negative is faster, and the sign is the whole reading. */
  @Test
  fun `a faster second half shows as a negative pace delta`() {
    val durations = listOf(2880L, 2880L, 2760L, 2760L)
    val analysis =
      analyse(
        durations.mapIndexed { index, seconds ->
          ProgramSessionRecord(
            session("s$index", "2026-08-0${index + 1}", week = 1),
            run = run(8.0, seconds),
          )
        }
      )!!

    assertEquals(-15, analysis.runTrends.single().paceDeltaSec)
  }

  /**
   * A single run against a single run is a comparison of two days' weather, sleep and terrain. The
   * group is reported as uncomparable rather than dropped, so the model can see the gap instead of
   * explaining a silence.
   */
  @Test
  fun `a group with too few runs yields no trend but is still reported`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1), run = run(8.0, 2880)),
          ProgramSessionRecord(session("s2", "2026-08-03", week = 1), run = run(8.0, 2760)),
          ProgramSessionRecord(session("s3", "2026-08-05", week = 1), run = run(8.0, 2700)),
        )
      )!!

    assertTrue(analysis.runTrends.isEmpty())
    assertEquals(listOf(Intensity.EASY to 3), analysis.runGroupsTooSmall)
  }

  /** With an odd count the middle run belongs to neither half rather than tipping one of them. */
  @Test
  fun `an odd number of runs leaves the middle one out of both halves`() {
    val analysis =
      analyse(
        (1..5).map {
          ProgramSessionRecord(session("s$it", "2026-08-0$it", week = 1), run = run(8.0, 2880))
        }
      )!!

    val trend = analysis.runTrends.single()
    assertEquals(2, trend.earlyCount)
    assertEquals(2, trend.lateCount)
  }

  /** Only completed runs are measured. A skipped one contributes nothing but its absence. */
  @Test
  fun `skipped runs are not part of the comparison`() {
    val analysis =
      analyse(
        (1..4).map {
          ProgramSessionRecord(
            session(
              "s$it",
              "2026-08-0$it",
              week = 1,
              status = if (it == 2) SessionStatus.SKIPPED else SessionStatus.COMPLETED,
            ),
            run = run(8.0, 2880),
          )
        }
      )!!

    assertEquals(3, analysis.runs.size)
    assertTrue(analysis.runTrends.isEmpty())
  }

  @Test
  fun `a run row carries the measurements the comparison needs`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(
            session("s1", "2026-08-01", week = 2, intensity = Intensity.MODERATE),
            outcome =
              ActiveWorkoutOutcome(
                guided = GuidedProgress(done = 1, rounds = 1, perRound = 1),
                sessionRpe = 6,
                feel = "Sopiva",
              ),
            run = run(10.0, 3000, avgHr = 155, load = 74),
          )
        )
      )!!

    val row = analysis.runs.single()
    assertEquals(LocalDate.of(2026, 8, 1), row.date)
    assertEquals(2, row.week)
    assertEquals(Intensity.MODERATE, row.plannedIntensity)
    assertEquals(300, row.paceSecPerKm)
    assertEquals(155, row.avgHeartRate)
    assertEquals(74, row.trainingLoad)
    assertEquals(6, row.rpe)
    assertEquals("Sopiva", row.feel)
  }

  // ------------------------------------------------------------------ strength

  @Test
  fun `strength names the movements the plan kept coming back to`() {
    val programme =
      listOf(Exercise(name = "Kyykky", reps = 10), Exercise(name = "Punnerrus", reps = 12))
    val analysis =
      analyse(
        (1..3).map {
          ProgramSessionRecord(
            session("s$it", "2026-08-0$it", week = 1, type = WorkoutType.STRENGTH, exercises = programme),
            outcome =
              ActiveWorkoutOutcome(
                guided = GuidedProgress(done = 2, rounds = 1, perRound = 2),
                sessionRpe = 5,
              ),
          )
        }
      )!!

    val strength = analysis.strength!!
    assertEquals(3, strength.completed)
    assertEquals(3, strength.fullyTickedOff)
    assertEquals(listOf("Kyykky", "Punnerrus"), strength.recurringMovements)
  }

  /** A movement done once is a detail of one session, and this report is not about one session. */
  @Test
  fun `a movement that appears once is not called recurring`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(
            session(
              "s1",
              "2026-08-01",
              week = 1,
              type = WorkoutType.STRENGTH,
              exercises = listOf(Exercise(name = "Kertaluontoinen", reps = 5)),
            )
          )
        )
      )!!

    assertTrue(analysis.strength!!.recurringMovements.isEmpty())
  }

  /** A plan with no strength work says nothing about strength, rather than saying zero. */
  @Test
  fun `a running-only plan has no strength section`() {
    val analysis = analyse(listOf(ProgramSessionRecord(session("s1", "2026-08-01", week = 1))))!!

    assertNull(analysis.strength)
  }

  // ------------------------------------------------------------------ the subjective half

  /**
   * The case the owner named: the numbers improve while the person keeps saying it is heavy. The
   * aggregate has to keep the two halves apart for that to be visible at all.
   */
  @Test
  fun `feedback keeps the early and late halves apart`() {
    val rpes = listOf(4, 4, 8, 8)
    val analysis =
      analyse(
        rpes.mapIndexed { index, rpe ->
          ProgramSessionRecord(
            session("s$index", "2026-08-0${index + 1}", week = 1),
            outcome =
              ActiveWorkoutOutcome(
                guided = GuidedProgress(done = 1, rounds = 1, perRound = 1),
                sessionRpe = rpe,
                feel = if (rpe > 6) "Raskas" else "Sopiva",
              ),
          )
        }
      )!!

    val feedback = analysis.feedback
    assertEquals(6.0, feedback.meanRpe!!, 0.001)
    assertEquals(4.0, feedback.earlyMeanRpe!!, 0.001)
    assertEquals(8.0, feedback.lateMeanRpe!!, 0.001)
    assertEquals(listOf("Raskas" to 2), feedback.lateFeelCounts)
  }

  /** Nothing rated is not an RPE of zero. */
  @Test
  fun `feedback with no ratings reports none`() {
    val analysis = analyse(listOf(ProgramSessionRecord(session("s1", "2026-08-01", week = 1))))!!

    assertEquals(0, analysis.feedback.ratedSessions)
    assertNull(analysis.feedback.meanRpe)
  }

  // ------------------------------------------------------------------ recovery

  @Test
  fun `recovery is compared across the programme when there are enough mornings`() {
    val days =
      (1..6).associate { day ->
        LocalDate.of(2026, 8, day) to
          DailyRecovery(date = "2026-08-0$day", averageHrvMs = 40 + day, restingHeartRate = 52 - day)
      }
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(session("s2", "2026-08-06", week = 1)),
        ),
        recovery = days,
      )!!

    val trend = analysis.recovery!!
    assertEquals(6, trend.days)
    // Six mornings split three and three: 41/42/43 against 44/45/46.
    assertEquals(42, trend.earlyMeanHrvMs)
    assertEquals(45, trend.lateMeanHrvMs)
  }

  /** Days outside the programme's own dates are somebody else's training. */
  @Test
  fun `recovery outside the programme is ignored`() {
    val days =
      (1..8).associate { day ->
        LocalDate.of(2026, 7, day) to DailyRecovery(date = "2026-07-0$day", averageHrvMs = 40)
      }
    val analysis =
      analyse(listOf(ProgramSessionRecord(session("s1", "2026-08-01", week = 1))), recovery = days)!!

    assertNull(analysis.recovery)
  }

  /** Same rule as the runs: two a side, or no comparison. */
  @Test
  fun `too few mornings produce no recovery trend`() {
    val days =
      (1..3).associate { day ->
        LocalDate.of(2026, 8, day) to DailyRecovery(date = "2026-08-0$day", averageHrvMs = 40)
      }
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(session("s2", "2026-08-03", week = 1)),
        ),
        recovery = days,
      )!!

    assertNull(analysis.recovery)
  }

  // ------------------------------------------------------------------ changes and what is left

  /**
   * A lightened session goes on to be completed, and its status then says COMPLETED. The flag on the
   * session is what remembers that the plan was changed, so that is what is read.
   */
  @Test
  fun `a completed session that was lightened still counts as a change`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1, lighter = true)),
          ProgramSessionRecord(session("s2", "2026-08-03", week = 1, status = SessionStatus.RESCHEDULED)),
        )
      )!!

    assertEquals(
      listOf(ProgramChangeKind.LIGHTER, ProgramChangeKind.RESCHEDULED),
      analysis.changes.map { it.kind },
    )
  }

  @Test
  fun `what remains is counted by type and by week`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(
            session("s2", "2026-09-20", week = 8, status = SessionStatus.PLANNED, distanceKm = 10.0)
          ),
          ProgramSessionRecord(
            session(
              "s3",
              "2026-09-22",
              week = 8,
              type = WorkoutType.STRENGTH,
              status = SessionStatus.PLANNED,
            )
          ),
        )
      )!!

    val remaining = analysis.remaining!!
    assertEquals(2, remaining.sessions)
    assertEquals(1, remaining.weeks)
    assertEquals(1, remaining.byType[WorkoutType.RUNNING])
    assertEquals(1, remaining.byType[WorkoutType.STRENGTH])
    assertEquals(10.0, remaining.plannedRunKm!!, 0.001)
  }

  /** A session the plan never dated cannot be placed on a timeline, and is not guessed at. */
  @Test
  fun `a session with an unreadable date is left out rather than placed`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(session("s1", "2026-08-01", week = 1)),
          ProgramSessionRecord(session("bad", "ei-päivä", week = 1)),
        )
      )

    assertNotNull(analysis)
    assertEquals(1, analysis!!.adherence.planned)
  }

  // --------------------------------------------------------------- heart-rate zone distribution

  private fun runWithZones(
    upper: List<Int>?,
    seconds: List<Int>,
    distanceKm: Double = 8.0,
    durationSec: Long = 2700,
  ): CompletedRunMetrics =
    run(distanceKm, durationSec).copy(heartRateZones = heartRateZones(upper, seconds))

  @Test
  fun `zone times are added up within each planned intensity`() {
    val bounds = listOf(120, 145, 160, 172, 190)
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(
            session("s1", "2026-08-03", week = 1),
            run = runWithZones(bounds, listOf(300, 1800, 120, 0, 0)),
          ),
          ProgramSessionRecord(
            session("s2", "2026-08-10", week = 2),
            run = runWithZones(bounds, listOf(200, 1500, 400, 0, 0)),
          ),
          ProgramSessionRecord(
            session("s3", "2026-08-17", week = 3, intensity = Intensity.HARD),
            run = runWithZones(bounds, listOf(120, 300, 600, 900, 180)),
          ),
        )
      )!!

    val easy = analysis.zoneSplits.single { it.intensity == Intensity.EASY }
    assertEquals(2, easy.runs)
    assertEquals(500L, easy.zones[0].seconds)
    assertEquals(3300L, easy.zones[1].seconds)
    assertEquals("Z2 (121–145)", easy.zones[1].label)
    assertEquals(76, easy.percentOf(easy.zones[1]))

    val hard = analysis.zoneSplits.single { it.intensity == Intensity.HARD }
    assertEquals(1, hard.runs)
    assertEquals(900L, hard.zones[3].seconds)
  }

  /**
   * A run the strap recorded nothing for is not a run spent in Z1 — folding it in as zeros would
   * make every group look easier than it was.
   */
  @Test
  fun `runs without zone data are counted out rather than counted as zero`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(
            session("s1", "2026-08-03", week = 1),
            run = runWithZones(listOf(120, 145), listOf(300, 900)),
          ),
          ProgramSessionRecord(session("s2", "2026-08-10", week = 2), run = run(8.0, 2700)),
        )
      )!!

    val easy = analysis.zoneSplits.single()
    assertEquals(1, easy.runs)
    assertEquals(1200L, easy.totalSeconds)
  }

  /** Zones move when the threshold is recomputed; quoting one week's bounds for both would be false. */
  @Test
  fun `a zone table that moved during the programme loses its beat ranges`() {
    val analysis =
      analyse(
        listOf(
          ProgramSessionRecord(
            session("s1", "2026-08-03", week = 1),
            run = runWithZones(listOf(120, 145, 160), listOf(300, 900, 60)),
          ),
          ProgramSessionRecord(
            session("s2", "2026-08-10", week = 2),
            run = runWithZones(listOf(124, 149, 163), listOf(200, 800, 90)),
          ),
        )
      )!!

    val easy = analysis.zoneSplits.single()
    assertEquals("Z2", easy.zones[1].label)
    assertNull(easy.zones[1].bpmRange)
    assertEquals(1700L, easy.zones[1].seconds)
  }

  @Test
  fun `a programme whose runs carried no zones has no zone section at all`() {
    val analysis =
      analyse(
        listOf(ProgramSessionRecord(session("s1", "2026-08-03", week = 1), run = run(8.0, 2700)))
      )!!

    assertTrue(analysis.zoneSplits.isEmpty())
  }
}
