package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
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

  // ------------------------------------------------------------------ contributors (ADR-014)

  /**
   * Oura's own breakdown reaches the prompt labelled `/100`, never bare — the reason rule 2 exists
   * is exactly so this can never be misread as the HRV-in-ms or leposyke-in-bpm lines above, which
   * are measurements rather than Oura's opinion of them.
   */
  @Test
  fun `contributors are labelled n over 100, distinct from the raw measurements`() {
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
                  averageHrvMs = 61,
                  activityRecoveryTime = 62,
                  readinessContributors =
                    ReadinessContributors(hrvBalance = 85, restingHeartRate = 90),
                )
            ),
        )
      )

    assertTrue(prompt.contains("HRV 61 ms"))
    assertTrue(prompt.contains("HRV-tasapaino 85/100"))
    assertTrue(prompt.contains("palautumisaika (7 vrk) 62/100"))
    assertTrue(prompt.contains("leposykkeen pisteytys 90/100"))
  }

  /**
   * The gap this note closes: a real request went out with readiness 91 and `recovery_time`
   * flagged "Pay attention" in Oura's own app, and the answer that came back only reasoned about
   * the good night — nothing in the prompt said the two could disagree. `recovery_time` answers a
   * week-long question, not a this-morning one, and a good night does not answer it.
   */
  @Test
  fun `recovery_time carries a note that it can outweigh a good night`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay =
            mapOf(day to DailyRecovery(date = day.toString(), readiness = 91, activityRecoveryTime = 38)),
        )
      )

    assertTrue(prompt.contains("eri asia kuin yllä oleva yön palautumislukema"))
    assertTrue(prompt.contains("riittää yksinään perusteeksi kevyempään suositukseen"))
  }

  /** The note is about `recovery_time` specifically — it has nothing to say when that is absent. */
  @Test
  fun `the recovery_time note is omitted when there is no recovery_time`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay =
            mapOf(
              day to
                DailyRecovery(
                  date = day.toString(),
                  readinessContributors = ReadinessContributors(hrvBalance = 85),
                )
            ),
        )
      )

    assertFalse(prompt.contains("eri asia kuin"))
  }

  /** Nothing to explain the score with is nothing written — no heading standing over a blank section. */
  @Test
  fun `omits the contributors section when there is nothing to explain the score with`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay = mapOf(day to DailyRecovery(date = day.toString(), readiness = 68)),
        )
      )

    assertFalse(prompt.contains("erittely"))
  }

  /**
   * Ten more numbers on every line of a week-long trend would swamp it, so the breakdown is written
   * only for the one day the analysis is about — never for the days the trend section covers.
   */
  @Test
  fun `the breakdown covers only the day being analysed, not the whole trend`() {
    val yesterday = day.minusDays(1)
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay =
            mapOf(
              day to DailyRecovery(date = day.toString(), activityRecoveryTime = 62),
              yesterday to DailyRecovery(date = yesterday.toString(), activityRecoveryTime = 40),
            ),
        )
      )

    assertTrue(prompt.contains("$day: palautumisaika (7 vrk) 62/100"))
    assertFalse(prompt.contains("$yesterday: palautumisaika"))
  }

  @Test
  fun `an upcoming prompt carries today's contributor breakdown`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          recoveryByDay = mapOf(day to DailyRecovery(date = day.toString(), activityRecoveryTime = 55)),
        )
      )

    assertTrue(prompt.contains("## Tämän päivän palautumisen erittely"))
    assertTrue(prompt.contains("palautumisaika (7 vrk) 55/100"))
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
          load = DailyTrainingLoad(date = day.toString(), acute = 42.0, chronic = null),
        )
      )

    assertFalse(prompt.contains("Kuormitus"))
  }

  @Test
  fun `an upcoming prompt omits the load section entirely when there is none`() {
    val prompt = builder.upcoming(UpcomingAnalysisInput(type = WorkoutType.RUNNING, date = day))

    assertFalse(prompt.contains("Kuormitus"))
  }

  @Test
  fun `an upcoming prompt carries both loads and their difference`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          date = day,
          load = DailyTrainingLoad(date = day.toString(), acute = 60.0, chronic = 50.0),
        )
      )

    assertTrue(prompt.contains("Akuutti kuormitus"))
    assertTrue(prompt.contains("Krooninen kuormitus"))
    // Fitness minus fatigue: negative here, which is the interesting direction.
    assertTrue(prompt.contains("-10"))
  }

  /**
   * The load figures carry the day they describe.
   *
   * This is the guard against the bug that produced it: load decays daily, so an undated figure
   * cannot be checked for staleness. The first version read acute and chronic load from the newest
   * *activity*, where they are frozen at the moment of that session — a three-day-old run reported a
   * TSB of -5.9 when the athlete's true figure that morning was -0.6. Naming the date makes a stale
   * number visible in the prompt instead of silent.
   */
  @Test
  fun `the load section names the day it describes`() {
    val prompt =
      builder.upcoming(
        UpcomingAnalysisInput(
          type = WorkoutType.RUNNING,
          // The session is on the 19th, but the freshest load row is from the 17th.
          date = LocalDate.of(2026, 8, 19),
          load = DailyTrainingLoad(date = "2026-08-17", acute = 11.5, chronic = 10.9),
        )
      )

    assertTrue("the reader cannot tell how current the figures are", prompt.contains("Kuormitus (2026-08-17)"))
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

  /**
   * A real answer recited seven numbers already visible on the user's own screen back to them —
   * "palautuminen 91, uni 89, HRV 44 ms ja leposyke 48. ... palautuminen oli 67, HRV 20 ms ja
   * leposyke 58" — out of a 110-word budget meant to hold a verdict. Both the guardrail and the
   * task question that used to invite it ("perustele ... luvuilla") are covered here.
   */
  @Test
  fun `both prompts forbid reciting the input numbers back as a list`() {
    val completed =
      builder.completed(CompletedAnalysisInput(type = WorkoutType.RUNNING, date = day))
    val upcoming = builder.upcoming(UpcomingAnalysisInput(type = WorkoutType.RUNNING, date = day))

    assertTrue(completed.contains("Älä listaa annettuja lukuja takaisin käyttäjälle"))
    assertTrue(upcoming.contains("Älä listaa annettuja lukuja takaisin käyttäjälle"))
    assertTrue(completed.contains("älä luettele lukuja uudelleen"))
    assertTrue(upcoming.contains("älä luettele lukuja uudelleen"))
  }

  /** Pure: the same input twice is the same string, so the "Näytä pyyntö" panel cannot drift. */
  @Test
  fun `is deterministic`() {
    val input = CompletedAnalysisInput(type = WorkoutType.STRENGTH, date = day)

    assertTrue(builder.completed(input) == builder.completed(input))
  }
  // ------------------------------------------------------------------ the guided workout

  private val programme =
    listOf(
      Exercise(name = "Bulgarialainen askelkyykky", reps = 8, perSide = true),
      Exercise(name = "Kahvakuulaheilautus", reps = 15),
      Exercise(name = "Penkkipunnerrus", sets = 3, reps = 8, weightKg = 40.0),
      Exercise(name = "Sivulankku", durationSec = 20, perSide = true),
    )

  /**
   * The gap this whole feature closes.
   *
   * Before it, a completed strength session reached the model as a duration, an intensity and a
   * paragraph of prose, and the answer said — correctly — that the movements, reps and loads were
   * unknown. They were in the database throughout; nothing sent them.
   */
  @Test
  fun `sends the planned movements with their reps and loads`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          plannedRounds = 2,
          exercises = programme,
        )
      )

    assertTrue(prompt.contains("## Suunniteltu ohjelma"))
    assertTrue(prompt.contains("- 2 kierrosta, 4 liikettä kierroksella"))
    assertTrue(prompt.contains("- Bulgarialainen askelkyykky: 8 toistoa / puoli"))
    assertTrue(prompt.contains("- Kahvakuulaheilautus: 15 toistoa"))
    assertTrue(prompt.contains("- Penkkipunnerrus: 3 sarjaa × 8 toistoa, 40 kg"))
    assertTrue(prompt.contains("- Sivulankku: 20 s / puoli"))
  }

  /** A ramp is the reason `setPlan` exists: every set's own load, spelled out. */
  @Test
  fun `spells out a ramp set by set`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises =
            listOf(
              Exercise(
                name = "Maastaveto",
                setPlan =
                  listOf(
                    ExerciseSet(weightKg = 25.0, reps = 5),
                    ExerciseSet(weightKg = 35.0, reps = 5),
                    ExerciseSet(weightKg = 47.5, reps = 3),
                  ),
              )
            ),
        )
      )

    assertTrue(prompt.contains("- Maastaveto: 25 kg × 5 toistoa, 35 kg × 5 toistoa, 47,5 kg × 3 toistoa"))
  }

  /** The plan's band, not its lower edge: "6–8" is what it asks for. */
  @Test
  fun `sends a rep range as a range`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = listOf(Exercise(name = "Timanttipunnerrus", reps = 6, repsMin = 6, repsMax = 8)),
        )
      )

    assertTrue(prompt.contains("- Timanttipunnerrus: 6–8 toistoa"))
  }

  /**
   * The sentence the feature is for: every movement ticked means the plan above **is** the record
   * of what happened, and the model is told so rather than left to infer it.
   */
  @Test
  fun `a fully ticked workout reports the plan as carried out`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          plannedRounds = 2,
          exercises = programme,
          guided = GuidedProgress(done = 8, rounds = 2, perRound = 4),
        )
      )

    assertTrue(prompt.contains("## Toteutunut (ohjattu treeni)"))
    assertTrue(prompt.contains("Kaikki 8 liikesuoritusta kuitattiin tehdyksi"))
    assertTrue(prompt.contains("Ohjelma toteutui suunnitellusti"))
    // Nothing may read as unfinished when everything was finished. Asserted on the sentence the
    // partial branch writes, not on the word — the task instruction above legitimately uses it.
    assertFalse(prompt.contains("kuitattiin päättyneeksi"))
  }

  /** Stopping early is the other half of the same signal, and has to name what was left. */
  @Test
  fun `an abandoned workout names what was not done`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          plannedRounds = 2,
          exercises = programme,
          guided = GuidedProgress(done = 5, rounds = 2, perRound = 4),
        )
      )

    assertTrue(prompt.contains("5 / 8 liikesuoritusta kuitattiin tehdyksi"))
    assertTrue(prompt.contains("- Kierros 1: kaikki 4 liikettä tehty"))
    assertTrue(
      prompt.contains(
        "- Kierros 2: tehty Bulgarialainen askelkyykky; tekemättä Kahvakuulaheilautus, " +
          "Penkkipunnerrus, Sivulankku"
      )
    )
    assertFalse(prompt.contains("Ohjelma toteutui suunnitellusti"))
  }

  /**
   * A session finished without a single tick says exactly that — not that nothing is known about
   * it, and not that it was done.
   */
  @Test
  fun `a workout finished with nothing ticked says so`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = programme,
          guided = GuidedProgress(done = 0, rounds = 1, perRound = 4),
        )
      )

    assertTrue(prompt.contains("0 / 4 liikesuoritusta kuitattiin tehdyksi"))
    assertTrue(prompt.contains("- ei tehty yhtään liikettä"))
  }

  /**
   * "Kevyempi versio" swaps the movement list under a started session. The counts were taken
   * against the old one, so they stay; the names would be a different workout's, so they go.
   *
   * Naming the wrong movements as done is worse than naming none — it is the one failure mode
   * here that produces a confident, wrong analysis rather than a vaguer one.
   */
  @Test
  fun `does not name movements when the list no longer matches the count`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          // Two movements on screen, but the session was counted against four.
          exercises = programme.take(2),
          guided = GuidedProgress(done = 5, rounds = 2, perRound = 4),
        )
      )

    assertTrue(prompt.contains("5 / 8 liikesuoritusta kuitattiin tehdyksi"))
    assertFalse(prompt.contains("Kierros 1:"))
    assertFalse(prompt.contains("; tekemättä"))
  }

  /**
   * A session completed before any of this shipped, or from a screen with no guided list, has
   * nothing recorded. That is not the same as nothing done, and must not render as zero.
   */
  @Test
  fun `renders no guided section when nothing was recorded`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(type = WorkoutType.STRENGTH, date = day, exercises = programme)
      )

    assertFalse(prompt.contains("Toteutunut (ohjattu treeni)"))
    assertFalse(prompt.contains("liikesuoritusta"))
  }

  /** A plan with no structured movements renders no programme section rather than an empty one. */
  @Test
  fun `renders no programme section for a plan that defines no movements`() {
    val prompt =
      builder.completed(CompletedAnalysisInput(type = WorkoutType.RUNNING, date = day))

    assertFalse(prompt.contains("Suunniteltu ohjelma"))
  }

  /** Without this line the model keeps hedging about loads it has just been told were performed. */
  @Test
  fun `tells the model how to read a tick`() {
    val prompt =
      builder.completed(CompletedAnalysisInput(type = WorkoutType.STRENGTH, date = day))

    assertTrue(prompt.contains("Ohjatun treenin kuittaukset kertovat, mitkä liikkeet tehtiin"))
  }

  // ------------------------------------------------------------------ the clocks

  private val timedProgramme =
    listOf(
      Exercise(name = "Punnerrus", reps = 10, restSec = 45),
      Exercise(name = "Kahvakuulaheilautus", reps = 15, restSec = 60),
    )

  /**
   * The question the section exists to make answerable: were ten press-ups in a minute brisk, or
   * did they come slowly? The model can only ask that if it is handed the seconds, so the seconds
   * are handed over per movement and per round, beside what the plan asked for.
   */
  @Test
  fun `reports each movement's own time and the gap that followed it`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          plannedRounds = 2,
          exercises = timedProgramme,
          guided = GuidedProgress(done = 4, rounds = 2, perRound = 2),
          timing =
            ActiveWorkoutOutcome(
              guided = GuidedProgress(done = 4, rounds = 2, perRound = 2),
              durationSec = 600,
              netSec = 250,
              movementSeconds = mapOf("1:1" to 62L, "1:2" to 55L, "2:1" to 78L, "2:2" to 55L),
              restSeconds = mapOf("1:1" to 48L, "1:2" to 61L, "2:1" to 92L),
            )
        )
      )

    assertTrue(prompt.contains("## Toteutunut ajankäyttö (ohjattu treeni)"))
    assertTrue(prompt.contains("- nettoaika 4:10 (pelkät liikkeet)"))
    assertTrue(prompt.contains("- bruttoaika 10:00 (levot mukaan lukien)"))
    assertTrue(
      prompt.contains(
        "- Kierros 1 · Punnerrus (10 toistoa): suoritus 1:02, tauko jälkeen 0:48 (suunniteltu 0:45)"
      )
    )
    // The same movement one round later is its own line, because it was its own effort — and this
    // one took sixteen seconds longer and rested half a minute past its plan.
    assertTrue(
      prompt.contains(
        "- Kierros 2 · Punnerrus (10 toistoa): suoritus 1:18, tauko jälkeen 1:32 (suunniteltu 0:45)"
      )
    )
  }

  /** The lines are written in the order the session happened, not in whatever order a map holds. */
  @Test
  fun `orders the movements by round and then by position`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = timedProgramme,
          timing =
            ActiveWorkoutOutcome(
              guided = GuidedProgress(done = 4, rounds = 2, perRound = 2),
              movementSeconds = mapOf("2:2" to 40L, "1:2" to 30L, "2:1" to 35L, "1:1" to 25L),
            )
        )
      )

    val order =
      listOf("Kierros 1 · Punnerrus", "Kierros 1 · Kahvakuulaheilautus", "Kierros 2 · Punnerrus")
        .map { prompt.indexOf(it) }

    assertTrue(order.none { it < 0 })
    assertEquals(order.sorted(), order)
  }

  /**
   * The last movement of a session has no gap after it, and a plan may ask for no rest at all.
   * Neither is written as a rest of zero seconds, which would read as a set run straight into the
   * next one.
   */
  @Test
  fun `writes no rest where none was measured and no plan where none was given`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = listOf(Exercise(name = "Leuanveto", reps = 5)),
          timing =
            ActiveWorkoutOutcome(
              guided = GuidedProgress(done = 1, rounds = 1, perRound = 1),
              movementSeconds = mapOf("1:1" to 44L),
              restSeconds = mapOf("1:1" to 70L),
            )
        )
      )

    assertTrue(
      prompt.contains("- Kierros 1 · Leuanveto (5 toistoa): suoritus 0:44, tauko jälkeen 1:10")
    )
    // No plan to compare the gap against, so no comparison is written. (The closing sentence about
    // reading the numbers says "suunniteltuun", hence the parenthesis in the match.)
    assertFalse(prompt.contains("(suunniteltu"))
    assertFalse(prompt.contains("0:00"))
  }

  /** Without this the model would compare tempo against nothing and call every set "hyvä". */
  @Test
  fun `tells the model what the seconds are evidence of`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = timedProgramme,
          timing =
            ActiveWorkoutOutcome(
              guided = GuidedProgress(done = 1, rounds = 1, perRound = 2),
              movementSeconds = mapOf("1:1" to 62L),
            )
        )
      )

    assertTrue(prompt.contains("Suoritusaika kertoo tempon"))
    assertTrue(prompt.contains("miten hyvin edellisestä sarjasta palauduttiin"))
  }

  /**
   * The same guard the checklist keeps. "Kevyempi versio" can swap the movement list under a
   * session after it was timed, and position 2 then points at an exercise that was never done.
   * The seconds are still true, so they are kept; the names are not, so they are dropped.
   */
  @Test
  fun `drops the names when the movement list no longer has the shape that was timed`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          // Three movements now; the session was counted against two.
          exercises = timedProgramme + Exercise(name = "Etunojapunnerrus", reps = 20),
          timing =
            ActiveWorkoutOutcome(
              guided = GuidedProgress(done = 2, rounds = 1, perRound = 2),
              movementSeconds = mapOf("1:1" to 62L),
              restSeconds = mapOf("1:1" to 48L),
            )
        )
      )

    assertTrue(prompt.contains("- Kierros 1 · liike 1: suoritus 1:02, tauko jälkeen 0:48"))
    // Neither the wrong name nor a planned rest borrowed from the wrong exercise.
    assertFalse(prompt.contains("Kierros 1 · Punnerrus"))
    assertFalse(prompt.contains("(suunniteltu"))
  }

  /**
   * A session finished before the clocks existed has no measurements, and "0:00" is not the same
   * fact as "unmeasured" — the same rule the Oura layer keeps about a night the ring was off.
   */
  @Test
  fun `renders no timing section when nothing was measured`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = timedProgramme,
          guided = GuidedProgress(done = 4, rounds = 2, perRound = 2),
        )
      )

    assertFalse(prompt.contains("Toteutunut ajankäyttö"))
    assertFalse(prompt.contains("nettoaika"))
  }

  /** An outcome that carries a checklist but no clock readings is the same absence of evidence. */
  @Test
  fun `renders no timing section for an outcome with no clock readings`() {
    val prompt =
      builder.completed(
        CompletedAnalysisInput(
          type = WorkoutType.STRENGTH,
          date = day,
          exercises = timedProgramme,
          timing = ActiveWorkoutOutcome(guided = GuidedProgress(done = 4, rounds = 2, perRound = 2))
        )
      )

    assertFalse(prompt.contains("Toteutunut ajankäyttö"))
  }
}
