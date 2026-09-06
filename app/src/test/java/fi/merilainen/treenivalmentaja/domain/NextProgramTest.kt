package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next programme: how the app describes a generated plan, and what it asks the model for.
 *
 * The summary is tested hardest, because it is the thing the person reads before agreeing to
 * replace their calendar. It is computed from the plan's own sessions precisely so that a model
 * cannot describe its plan as something other than what it wrote.
 */
class NextProgramTest {

  private fun session(
    id: String,
    date: String,
    week: Int,
    type: WorkoutType = WorkoutType.RUNNING,
    intensity: Intensity? = Intensity.EASY,
    distanceKm: Double? = null,
    exercises: List<Exercise>? = null,
  ) =
    TrainingSession(
      id = id,
      planId = "next",
      type = type,
      weekNumber = week,
      scheduledDate = date,
      scheduledTime = "17:30",
      remindAtUtc = 0,
      distanceKm = distanceKm,
      intensity = intensity,
      exercises = exercises,
    )

  // ------------------------------------------------------------------ summarising a plan

  @Test
  fun `an empty plan cannot be summarised`() {
    assertNull(summariseProgramPlan("Tyhjä", null, emptyList()))
  }

  @Test
  fun `the summary counts the sessions the plan actually contains`() {
    val plan =
      listOf(
        session("a", "2026-09-14", 1, distanceKm = 8.0),
        session("b", "2026-09-17", 1, type = WorkoutType.STRENGTH, intensity = null),
        session("c", "2026-09-21", 2, distanceKm = 9.0),
        session("d", "2026-09-24", 2, type = WorkoutType.STRENGTH, intensity = null),
      )

    val summary = summariseProgramPlan("Syysjakso", "Peruskestävyyttä.", plan)!!

    assertEquals("Syysjakso", summary.name)
    assertEquals("Peruskestävyyttä.", summary.statedGoal)
    assertEquals(LocalDate.of(2026, 9, 14), summary.startDate)
    assertEquals(LocalDate.of(2026, 9, 24), summary.endDate)
    assertEquals(2, summary.weeks)
    assertEquals(4, summary.sessions)
    assertEquals(2.0, summary.sessionsPerWeek, 0.001)
    assertEquals(2, summary.byType[WorkoutType.RUNNING])
    assertEquals(2, summary.byType[WorkoutType.STRENGTH])
  }

  @Test
  fun `the run shape carries the weekly kilometres and the longest run`() {
    val plan =
      listOf(
        session("a", "2026-09-14", 1, distanceKm = 8.0),
        session("b", "2026-09-16", 1, distanceKm = 5.0),
        session("c", "2026-09-21", 2, distanceKm = 12.0, intensity = Intensity.MODERATE),
      )

    val run = summariseProgramPlan("Juoksujakso", null, plan)!!.runShape!!

    assertEquals(3, run.sessions)
    assertEquals(13.0, run.weeklyKmFirstWeek!!, 0.001)
    assertEquals(12.0, run.weeklyKmLastWeek!!, 0.001)
    assertEquals(12.0, run.longestRunKm!!, 0.001)
    assertEquals(2, run.byIntensity[Intensity.EASY])
    assertEquals(1, run.byIntensity[Intensity.MODERATE])
  }

  /** A plan with no running says nothing about running rather than saying zero kilometres. */
  @Test
  fun `a strength-only plan has no run shape`() {
    val plan =
      listOf(session("a", "2026-09-14", 1, type = WorkoutType.STRENGTH, intensity = null))

    assertNull(summariseProgramPlan("Voimajakso", null, plan)!!.runShape)
  }

  @Test
  fun `rising weekly kilometres read as a rising progression`() {
    val plan =
      (1..4).map { week ->
        session("r$week", "2026-09-${13 + week * 7}", week, distanceKm = 6.0 + week * 2)
      }

    assertEquals(NextProgramProgression.RISING, summariseProgramPlan("Nouseva", null, plan)!!.progression)
  }

  /** A kilometre of wobble is not a trend, and a taper week is not a decline. */
  @Test
  fun `a plan that holds its volume reads as flat`() {
    val plan =
      (1..4).map { week ->
        session("r$week", "2026-09-${13 + week * 7}", week, distanceKm = if (week == 2) 8.5 else 8.0)
      }

    assertEquals(NextProgramProgression.FLAT, summariseProgramPlan("Tasainen", null, plan)!!.progression)
  }

  /** One week is a week, not a direction. */
  @Test
  fun `a single week has no progression`() {
    val plan = listOf(session("a", "2026-09-14", 1, distanceKm = 8.0))

    assertEquals(NextProgramProgression.UNKNOWN, summariseProgramPlan("Yksi", null, plan)!!.progression)
  }

  // ------------------------------------------------------------------ comparing with the last one

  private fun previousProgramme(): TrainingProgramAnalysis =
    buildProgramAnalysis(
      planName = "Edellinen",
      planDescription = null,
      sessions =
        (1..4).map { week ->
          ProgramSessionRecord(
            session("p$week", "2026-08-0$week", week, distanceKm = 8.0).copy(
              status = SessionStatus.COMPLETED
            ),
            run =
              CompletedRunMetrics(
                activityId = "a$week",
                sportType = "Run",
                startTimeUtc = 0,
                movingTimeSec = 2880,
                distanceKm = 8.0,
                avgSpeedMps = 8000.0 / 2880,
              ),
          )
        },
      today = LocalDate.of(2026, 9, 6),
    )!!

  /**
   * Compared against what was **run**, not against what the old plan asked for: the previous plan's
   * intentions are not the level the person reached, and a next block anchored to them would be
   * anchored to a wish.
   */
  @Test
  fun `changes are measured against the previous programme's actual weekly kilometres`() {
    val plan = (1..4).map { week -> session("n$week", "2026-09-0$week", week, distanceKm = 10.0) }

    val changes = summariseProgramPlan("Uusi", null, plan, previous = previousProgramme())!!
      .changesFromPrevious

    assertTrue(changes.any { it.contains("8.0 km (toteutunut)") && it.contains("10.0 km (suunniteltu)") })
  }

  /**
   * The sessions line reads the same way, and says so.
   *
   * Both halves of both comparisons are labelled because they are not the same kind of number: one
   * is what happened and the other is what is proposed. Two adjacent lines silently comparing
   * different things is how a plan that doubles the training looks reasonable.
   */
  @Test
  fun `the sessions line compares what was done with what is planned, and labels both`() {
    // Two a week against the previous programme's one, so the line is a comparison rather than the
    // "suunnilleen yhtä monta" shorthand.
    val plan =
      (1..4).flatMap { week ->
        listOf(
          session("n$week-a", "2026-09-0$week", week, distanceKm = 10.0),
          session("n$week-b", "2026-09-1$week", week, distanceKm = 10.0),
        )
      }

    val changes = summariseProgramPlan("Uusi", null, plan, previous = previousProgramme())!!
      .changesFromPrevious

    assertTrue(changes.any { it == "Harjoituksia viikossa 1.0 (toteutunut) → 2.0 (suunniteltu)" })
  }

  /** A plan that keeps the same rhythm says so, rather than printing two identical numbers. */
  @Test
  fun `an unchanged number of sessions is said in words`() {
    val plan = (1..4).map { week -> session("n$week", "2026-09-0$week", week, distanceKm = 10.0) }

    val changes = summariseProgramPlan("Uusi", null, plan, previous = previousProgramme())!!
      .changesFromPrevious

    assertTrue(changes.any { it == "Harjoituksia viikossa suunnilleen yhtä monta kuin edellisessä toteutui" })
  }

  /**
   * A programme only half carried out must not be compared against its own intentions — that is
   * exactly the case where doing so hides the jump.
   */
  @Test
  fun `a poorly followed previous programme is compared by what was actually done`() {
    val previous =
      buildProgramAnalysis(
        planName = "Jäi kesken",
        planDescription = null,
        sessions =
          (1..8).map { week ->
            ProgramSessionRecord(
              session("p$week", "2026-08-0$week", week).copy(
                // Two of eight done: planned would say 1.0 a week, done says 0.25.
                status = if (week <= 2) SessionStatus.COMPLETED else SessionStatus.SKIPPED
              )
            )
          },
        today = LocalDate.of(2026, 9, 6),
      )!!
    val plan = (1..8).map { week -> session("n$week", "2026-09-0$week", week, distanceKm = 8.0) }

    val changes = summariseProgramPlan("Uusi", null, plan, previous = previous)!!.changesFromPrevious

    assertTrue(changes.any { it.contains("0.3 (toteutunut) → 1.0 (suunniteltu)") })
  }

  /** With nothing to compare against there is nothing to say, rather than a list of nulls. */
  @Test
  fun `a plan with no predecessor lists no changes`() {
    val plan = listOf(session("a", "2026-09-14", 1, distanceKm = 8.0))

    assertTrue(summariseProgramPlan("Ensimmäinen", null, plan)!!.changesFromPrevious.isEmpty())
  }

  // ------------------------------------------------------------------ the prompt

  private val builder = NextProgramPromptBuilder()

  private fun prompt(
    request: NextProgramRequest = NextProgramRequest(goal = NextProgramGoal.BALANCED),
    finalReport: String? = null,
  ) =
    builder.build(
      analysis = previousProgramme(),
      request = request,
      startDate = LocalDate.of(2026, 9, 14),
      timeZone = "Europe/Helsinki",
      finalReport = finalReport,
    )

  /**
   * The rule the whole feature turns on. A plan that starts from a generic baseline throws away the
   * block that was just done, which is the one thing the person paid eight weeks for.
   */
  @Test
  fun `the prompt forbids starting from a generic baseline`() {
    assertTrue(
      prompt().contains("Älä aloita\n   geneeriseltä aloittelijatasolta")
    )
  }

  /** A programme ending is not evidence that the next one should be harder. */
  @Test
  fun `the prompt forbids escalating after a programme that went badly`() {
    val text = prompt()

    assertTrue(text.contains("Edellisen jakson päättyminen ei ole peruste vaikeuttaa."))
    assertTrue(text.contains("pidä kuorma ennallaan tai kevennä sitä"))
  }

  /** The level the plan continues from is the late half of the trends, and it is labelled as such. */
  @Test
  fun `the prompt states where the person ended up`() {
    val text = prompt()

    assertTrue(text.contains("## Edellinen jakso ja nykyinen taso"))
    assertTrue(text.contains("-juoksujen taso jakson lopussa"))
  }

  /** The one piece of evidence the measurements cannot supply. */
  @Test
  fun `the user's own verdict on the block is passed through with its instruction`() {
    val text = prompt(NextProgramRequest(goal = NextProgramGoal.BALANCED, fit = ProgramFit.TOO_HARD))

    assertTrue(text.contains("Käyttäjän oma arvio koko jaksosta: liian raskas"))
    assertTrue(text.contains("Kevennä kokonaiskuormaa, vaikka mittarit näyttäisivät kehitystä."))
  }

  /** Skipped is absent, not "sopiva" — the same rule the rest of the app keeps. */
  @Test
  fun `a skipped verdict is reported as missing rather than as neutral`() {
    val text = prompt()

    assertTrue(text.contains("Käyttäjä ei antanut kokonaisarviota jaksosta."))
    assertFalse(text.contains("Käyttäjän oma arvio koko jaksosta"))
  }

  @Test
  fun `the chosen emphasis reaches the model with its direction spelled out`() {
    val text =
      prompt(
        NextProgramRequest(goal = NextProgramGoal.RUNNING_SPEED, ownGoal = "  10 km alle 50 min  ")
      )

    assertTrue(text.contains("Valittu painotus: Nopeampi juoksuvauhti"))
    assertTrue(text.contains("Lisää hallittua vauhtiharjoittelua"))
    assertTrue(text.contains("Käyttäjän oma tavoite, omin sanoin: 10 km alle 50 min"))
  }

  @Test
  fun `the final report is included when there is one`() {
    val text = prompt(finalReport = "## Tulkinta\nKevyet juoksut kulkivat nopeammin.")

    assertTrue(text.contains("## Juuri kirjoittamasi loppuraportti edellisestä jaksosta"))
    assertTrue(text.contains("Kevyet juoksut kulkivat nopeammin."))
  }

  @Test
  fun `no final report means no section for one`() {
    assertFalse(prompt().contains("loppuraportti edellisestä jaksosta"))
  }

  // ------------------------------------------------------------------ the schema contract

  /**
   * Every rule the validator enforces has to be visible to the model, because a rule it cannot see
   * is a rejection it cannot avoid. These assertions are the contract between this prompt and
   * `PlanValidator`; if that gains a rule, one of them should start looking incomplete.
   */
  @Test
  fun `the prompt states the schema rules the validator will apply`() {
    val text = prompt(NextProgramRequest(goal = NextProgramGoal.BALANCED, weeks = 8))

    assertTrue(text.contains("`schemaVersion` on tasan 1."))
    assertTrue(text.contains("koko dokumentissa yksilöllisiä"))
    assertTrue(text.contains("Viikkoja on 8, `weekNumber` 1..8"))
    assertTrue(text.contains("Europe/Helsinki"))
    assertTrue(text.contains("2026-09-14"))
    assertTrue(text.contains("vähintään yksi seuraavista: `durationMin`, `distanceKm` tai"))
    assertTrue(text.contains("vähintään `reps` tai `durationSec`"))
  }

  /** The instruction most often lost, so it is stated twice — at the schema and at the end. */
  @Test
  fun `the prompt demands bare JSON and repeats it last`() {
    val text = prompt()

    assertTrue(text.contains("Palauta **pelkkä JSON-dokumentti**"))
    assertTrue(text.trimEnd().endsWith("Ensimmäinen merkki on { ja viimeinen on }."))
  }
}
