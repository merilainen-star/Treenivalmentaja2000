package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What actually reaches the model when a whole programme is analysed.
 *
 * The assertions worth having here are the ones about *absence*: a comparison the app declined to
 * make must arrive as a stated absence, never as a missing line the model is left to explain, and
 * never as a zero.
 */
class ProgramReportPromptBuilderTest {

  private val builder = ProgramReportPromptBuilder()
  private val today = LocalDate.of(2026, 9, 6)

  private fun session(
    id: String,
    date: String,
    week: Int,
    type: WorkoutType = WorkoutType.RUNNING,
    status: SessionStatus = SessionStatus.COMPLETED,
    intensity: Intensity? = Intensity.EASY,
    distanceKm: Double? = null,
  ) =
    TrainingSession(
      id = id,
      planId = "p1",
      type = type,
      weekNumber = week,
      scheduledDate = date,
      scheduledTime = null,
      remindAtUtc = 0,
      distanceKm = distanceKm,
      intensity = intensity,
      status = status,
    )

  private fun run(distanceKm: Double, durationSec: Long, avgHr: Int? = null, maxHr: Int? = null) =
    CompletedRunMetrics(
      activityId = "a$durationSec",
      sportType = "Run",
      startTimeUtc = 0,
      movingTimeSec = durationSec,
      distanceKm = distanceKm,
      avgSpeedMps = distanceKm * 1000.0 / durationSec,
      avgHeartRate = avgHr,
      maxHeartRate = maxHr,
      trainingLoad = 62,
    )

  private fun analysisOf(records: List<ProgramSessionRecord>, goals: String? = null) =
    buildProgramAnalysis(
      planName = "Syksyn peruskuntokausi",
      planDescription = goals,
      sessions = records,
      today = today,
    )!!

  private val fourEasyRuns =
    listOf(152, 149, 141, 138).mapIndexed { index, hr ->
      ProgramSessionRecord(
        session("s$index", "2026-08-0${index + 1}", week = 1),
        outcome =
          ActiveWorkoutOutcome(
            guided = GuidedProgress(done = 1, rounds = 1, perRound = 1),
            sessionRpe = 5,
            feel = "Sopiva",
          ),
        run = run(8.0, 2880, avgHr = hr, maxHr = hr + 15),
      )
    }

  // ------------------------------------------------------------------ the frame

  @Test
  fun `an interim report names the programme, the week and the plan's own goals`() {
    val prompt =
      builder.build(
        analysisOf(
          fourEasyRuns +
            ProgramSessionRecord(session("open", "2026-09-20", week = 4, status = SessionStatus.PLANNED)),
          goals = "Peruskestävyyttä ja 10 km alle 55 min",
        ),
        ProgramReportKind.INTERIM,
      )

    assertTrue(prompt.contains("Tee väliraportti tästä harjoitusohjelmasta."))
    assertTrue(prompt.contains("- Nimi: Syksyn peruskuntokausi"))
    assertTrue(prompt.contains("kesken, viikko"))
    assertTrue(prompt.contains("Peruskestävyyttä ja 10 km alle 55 min"))
  }

  /** The two reports differ in what they may say about what comes next, so they differ in shape. */
  @Test
  fun `a final report asks for the before-and-after and the next block's emphasis`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertTrue(prompt.contains("Tee loppuraportti tästä päättyneestä harjoitusohjelmasta."))
    assertTrue(prompt.contains("## Alussa → lopussa"))
    assertTrue(prompt.contains("## Suositus seuraavalle jaksolle"))
    // A finished programme has nothing left, so the section is not written at all.
    assertFalse(prompt.contains("## Ohjelmasta jäljellä"))
  }

  /** The interim report is the one that has to weigh what is left against what has happened. */
  @Test
  fun `an interim report carries what remains of the plan`() {
    val prompt =
      builder.build(
        analysisOf(
          fourEasyRuns +
            ProgramSessionRecord(
              session("open", "2026-09-20", week = 4, status = SessionStatus.PLANNED, distanceKm = 12.0)
            )
        ),
        ProgramReportKind.INTERIM,
      )

    assertTrue(prompt.contains("## Ohjelmasta jäljellä"))
    assertTrue(prompt.contains("Suunniteltuja juoksukilometrejä jäljellä: 12,00 km"))
    assertFalse(prompt.contains("## Alussa → lopussa"))
  }

  // ------------------------------------------------------------------ the numbers

  @Test
  fun `every completed run keeps its own line`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertTrue(prompt.contains("## Juoksut, vanhimmasta uusimpaan"))
    assertTrue(
      prompt.contains("- 2026-08-01 (vk 1, suunniteltu teho kevyt): 8,00 km, 48:00, tahti 6:00 /km, syke 152, max 167")
    )
    // All four, not a sample and not an average.
    assertEquals4Runs(prompt)
  }

  private fun assertEquals4Runs(prompt: String) {
    val lines = prompt.lines().filter { it.startsWith("- 2026-08-0") }
    assertTrue("expected four run lines, got ${lines.size}", lines.size == 4)
  }

  /** The delta is handed over, not the job of finding it. */
  @Test
  fun `the run comparison is rendered already computed, with its direction spelled out`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertTrue(prompt.contains("## Juoksujen kehitys (sovelluksen laskema)"))
    assertTrue(prompt.contains("ensimmäiset 2 juoksua vs. viimeiset 2 juoksua"))
    assertTrue(prompt.contains("keskisyke 151 → 140 (-11)"))
    assertTrue(prompt.contains("tahti 6:00 /km → 6:00 /km (ei muutosta)"))
    // The reader has to know which way the sign points before it can mean anything.
    assertTrue(prompt.contains("Negatiivinen tahtimuutos tarkoittaa nopeampaa"))
  }

  /**
   * The absence rule, and the reason the aggregate reports small groups at all: a silence the model
   * has to account for is a silence it will account for wrongly.
   */
  @Test
  fun `a group too small to compare is stated as such rather than left out`() {
    val threeRuns =
      (1..3).map {
        ProgramSessionRecord(session("s$it", "2026-08-0$it", week = 1), run = run(8.0, 2880))
      }
    val prompt = builder.build(analysisOf(threeRuns), ProgramReportKind.FINAL)

    assertTrue(prompt.contains("Kevyt: 3 juoksua — liian vähän vertailuun, kehitystä ei ole laskettu"))
  }

  @Test
  fun `the completion rate says what it excludes`() {
    val prompt =
      builder.build(
        analysisOf(
          fourEasyRuns +
            ProgramSessionRecord(session("r", "2026-08-09", week = 2, status = SessionStatus.RESCHEDULED))
        ),
        ProgramReportKind.FINAL,
      )

    assertTrue(prompt.contains("- Toteutumisprosentti: 100 %"))
    assertTrue(prompt.contains("siirretyt ja poistetut"))
  }

  // ------------------------------------------------------------------ the subjective half

  @Test
  fun `the user's own feedback is rendered beside the measurements`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertTrue(prompt.contains("## Käyttäjän oma palaute"))
    assertTrue(prompt.contains("Koettu rasittavuus (RPE) keskimäärin: 5,0 / 10"))
    assertTrue(prompt.contains("Miltä tuntui: Sopiva 4 kertaa"))
  }

  /** Nothing rated is no section, rather than a section full of zeroes. */
  @Test
  fun `a programme with no feedback renders no feedback section`() {
    val prompt =
      builder.build(
        analysisOf(
          (1..4).map {
            ProgramSessionRecord(session("s$it", "2026-08-0$it", week = 1), run = run(8.0, 2880))
          }
        ),
        ProgramReportKind.FINAL,
      )

    assertFalse(prompt.contains("## Käyttäjän oma palaute"))
  }

  @Test
  fun `a running-only programme renders no strength section`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertFalse(prompt.contains("## Voima- ja lihaskuntoharjoittelu"))
  }

  // ------------------------------------------------------------------ the instructions

  /**
   * The three-section shape is the feature. A coach who cannot tell you which sentence is a
   * measurement, which is a reading and which is advice is producing prose rather than coaching.
   */
  @Test
  fun `the interim task asks for facts, interpretation and recommendation, in that order`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.INTERIM)

    val order =
      listOf("## Näin ohjelma on toteutunut", "## Tulkinta", "## Suositus").map { prompt.indexOf(it) }
    assertTrue(order.none { it < 0 })
    assertTrue(order == order.sorted())
    assertTrue(prompt.contains("äläkä lisää neljättä"))
  }

  /** The question a numbers-only report always gets wrong. */
  @Test
  fun `the interim task names the case where the data and the person disagree`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.INTERIM)

    assertTrue(prompt.contains("mittarit paranevat mutta harjoitukset tuntuvat jatkuvasti raskailta"))
  }

  /** A programme ending is not evidence that the next one should be harder. */
  @Test
  fun `the final task forbids escalating after a programme that went badly`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertTrue(
      prompt.contains("älä ehdota kovempaa jaksoa vain siksi\nettä edellinen päättyi")
    )
    // A recommendation, not a programme — nothing downstream of this prompt can save a plan.
    assertTrue(prompt.contains("ei valmis ohjelma, ei viikkokohtaista suunnitelmaa"))
  }

  @Test
  fun `the guardrails forbid inventing development and reasoning past a withheld comparison`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertTrue(prompt.contains("Jos data ei osoita kehitystä, sano että se ei osoita."))
    assertTrue(prompt.contains("älä tee siitä johtopäätöstä"))
    assertTrue(prompt.contains("Erota selvästi mitattu fakta, oma tulkintasi ja suositus."))
  }

  /**
   * Unlike the per-session analysis, this one wants headings and a document's length. Asserted so
   * that a future tidy-up that shares the session guardrails fails here rather than silently
   * turning the report into 110 words with no structure.
   */
  @Test
  fun `the report does not inherit the session analysis's no-headings rule`() {
    val prompt = builder.build(analysisOf(fourEasyRuns), ProgramReportKind.FINAL)

    assertFalse(prompt.contains("Enintään 110 sanaa"))
    assertFalse(prompt.contains("ilman otsikoita"))
    assertTrue(prompt.contains("Käytä yllä pyydettyjä otsikoita."))
  }
}
