package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import java.util.Locale

/** What a completed session is judged on: what was asked for, what happened, and how the body was. */
data class CompletedAnalysisInput(
  val type: WorkoutType,
  val date: LocalDate,
  val plannedDurationMin: Int? = null,
  val plannedIntensity: Intensity? = null,
  val description: String? = null,
  /** What Oura recorded for the session, when it recorded anything. */
  val oura: CompletedSessionMetrics? = null,
  /** What the watch recorded, via intervals.icu. Richer than Oura for a run, absent for most else. */
  val run: CompletedRunMetrics? = null,
  /** The mornings around the session, keyed by day. Missing days are genuinely missing. */
  val recoveryByDay: Map<LocalDate, DailyRecovery> = emptyMap(),
)

/** What an upcoming session is judged on: what it asks for, against how recovery is trending. */
data class UpcomingAnalysisInput(
  val type: WorkoutType,
  val date: LocalDate,
  val plannedDurationMin: Int? = null,
  val plannedIntensity: Intensity? = null,
  val description: String? = null,
  val recoveryByDay: Map<LocalDate, DailyRecovery> = emptyMap(),
  /** Acute load — fatigue — as of the most recent activity that carried one. */
  val acuteLoad: Double? = null,
  /** Chronic load — fitness — as of the same activity. */
  val chronicLoad: Double? = null,
)

/**
 * Turns what the app knows into the exact text sent to Claude.
 *
 * **A pure function of its inputs** — no repository, no clock, no network — for the same reason
 * `ReadinessAdviceUseCase` is one: every branch here is a unit test rather than something only a
 * real week of training could produce. It is also the half of this feature worth testing at all;
 * the client is transport.
 *
 * Three rules run through everything below.
 *
 *  1. **Missing is omitted, never zero and never a dash.** A night the ring was not worn must not
 *     reach the model as an HRV of 0, which reads as a catastrophic reading rather than as no
 *     reading — the same discipline the whole Oura layer is built on. A line with nothing behind it
 *     is simply not written.
 *  2. **Every number is labelled with its unit**, because the model is being asked to reason about
 *     them and "HRV 61" and "HRV 61 ms" are not equally clear.
 *  3. **The output is what the user sees.** The "Näytä pyyntö" panel shows this string verbatim, so
 *     it is written to be readable by a person, not packed for a machine.
 */
class AnalysisPromptBuilder {

  fun completed(input: CompletedAnalysisInput): String = buildString {
    appendLine(ROLE)
    appendLine()
    appendLine("Analysoi tämä juuri tehty harjoitus.")
    appendLine()

    appendLine("## Suunniteltu harjoitus")
    appendLine("- Päivä: ${input.date}")
    appendLine("- Laji: ${input.type.title}")
    input.plannedDurationMin?.let { appendLine("- Suunniteltu kesto: $it min") }
    input.plannedIntensity?.let { appendLine("- Suunniteltu teho: ${it.title}") }
    input.description?.takeIf { it.isNotBlank() }?.let { appendLine("- Sisältö: ${it.trim()}") }
    appendLine()

    val performed = buildList {
      input.oura?.let { o ->
        add("kesto ${o.durationMin} min")
        o.distanceKm?.let { add(km(it)) }
        o.avgHeartRate?.let { add("keskisyke $it") }
        o.maxHeartRate?.let { add("maksimisyke $it") }
        o.calories?.let { add("$it kcal") }
      }
    }
    if (performed.isNotEmpty()) {
      appendLine("## Toteutunut (Oura)")
      performed.forEach { appendLine("- $it") }
      appendLine()
    }

    input.run?.let { r ->
      val watch = buildList {
        r.paceText?.let { add("tahti $it") }
        r.distanceKm?.let { add(km(it)) }
        add("kesto ${r.primaryDurationSec.formatDuration()}")
        r.avgHeartRate?.let { add("keskisyke $it") }
        r.maxHeartRate?.let { add("maksimisyke $it") }
        r.trainingLoad?.let { add("kuormitus $it") }
        r.intensityPercent?.let { add("intensiteetti $it %") }
        r.trimp?.let { add("TRIMP ${decimal(it)}") }
        r.elevationGainMeters?.let { add("nousu $it m") }
      }
      appendLine("## Toteutunut (kello, Intervals.icu)")
      watch.forEach { appendLine("- $it") }
      appendLine()
    }

    appendRecovery(
      heading = "## Palautuminen harjoituspäivänä ja sitä ennen",
      days = input.date.minusDays(TREND_DAYS_BACK)..input.date,
      recoveryByDay = input.recoveryByDay,
    )

    appendLine(COMPLETED_TASK)
    append(GUARDRAILS)
  }

  fun upcoming(input: UpcomingAnalysisInput): String = buildString {
    appendLine(ROLE)
    appendLine()
    appendLine("Neuvo, miten tämä tuleva harjoitus kannattaa toteuttaa.")
    appendLine()

    appendLine("## Suunniteltu harjoitus")
    appendLine("- Päivä: ${input.date}")
    appendLine("- Laji: ${input.type.title}")
    input.plannedDurationMin?.let { appendLine("- Suunniteltu kesto: $it min") }
    input.plannedIntensity?.let { appendLine("- Suunniteltu teho: ${it.title}") }
    input.description?.takeIf { it.isNotBlank() }?.let { appendLine("- Sisältö: ${it.trim()}") }
    appendLine()

    appendRecovery(
      heading = "## Palautumisen kehitys",
      days = input.date.minusDays(TREND_DAYS_BACK)..input.date,
      recoveryByDay = input.recoveryByDay,
    )

    // Written only when intervals.icu actually computed them. Both or neither: the pair is what
    // means something, and one alone invites the model to infer the other.
    if (input.acuteLoad != null && input.chronicLoad != null) {
      appendLine("## Kuormitus")
      appendLine("- Akuutti kuormitus (väsymys): ${decimal(input.acuteLoad)}")
      appendLine("- Krooninen kuormitus (kunto): ${decimal(input.chronicLoad)}")
      appendLine("- Erotus (TSB): ${decimal(input.chronicLoad - input.acuteLoad)}")
      appendLine()
    }

    appendLine(UPCOMING_TASK)
    append(GUARDRAILS)
  }

  /**
   * The recovery block, one line per day that has something on it.
   *
   * Days the app has never fetched are absent from the map; days Oura answered about with no numbers
   * in them are present but empty. Both produce no line, which is the honest rendering of each —
   * and the heading itself is omitted when no day in the range has anything, rather than standing
   * over a blank section.
   */
  private fun StringBuilder.appendRecovery(
    heading: String,
    days: ClosedRange<LocalDate>,
    recoveryByDay: Map<LocalDate, DailyRecovery>,
  ) {
    val lines =
      generateSequence(days.start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(days.endInclusive) }
        .mapNotNull { day ->
          val recovery = recoveryByDay[day] ?: return@mapNotNull null
          val parts = buildList {
            recovery.readiness?.let { add("palautuminen $it") }
            recovery.sleep?.let { add("uni $it") }
            recovery.averageHrvMs?.let { add("HRV $it ms") }
            recovery.restingHeartRate?.let { add("leposyke $it") }
            recovery.activity?.let { add("aktiivisuus $it") }
          }
          if (parts.isEmpty()) null else "- $day: ${parts.joinToString(", ")}"
        }
        .toList()
    if (lines.isEmpty()) return
    appendLine(heading)
    lines.forEach { appendLine(it) }
    appendLine()
  }

  private fun km(value: Double): String = String.format(FINNISH, "%.2f km", value)

  private fun decimal(value: Double): String = String.format(FINNISH, "%.1f", value)

  private companion object {

    val FINNISH: Locale = Locale("fi", "FI")

    /** A week of mornings around the session — enough to see a direction, short enough to read. */
    const val TREND_DAYS_BACK = 6L

    val ROLE =
      """
      Olet kokenut kestävyys- ja voimaharjoittelun valmentaja. Puhut suomea.
      Saat alla harjoituksen tiedot ja urheilijan palautumisdatan Oura-sormuksesta ja
      Suunto-kellosta. Kaikki luvut ovat mitattuja; puuttuvat rivit tarkoittavat, ettei mittausta
      ole — älä oleta niille arvoja.
      """
        .trimIndent()

    val COMPLETED_TASK =
      """
      ## Tehtävä
      Vastaa kolmeen kysymykseen yhtenäisenä tekstinä:
      1. Miten harjoitus meni suhteessa siihen, mitä oli suunniteltu?
      2. Miltä kuormitus näyttää sen aamun palautumislukemien ja viime päivien kehityksen valossa?
      3. Suositus seuraavaksi: painaako kovempaa, jatkaako suunnitelman mukaan, vai levätäkö.
         Perustele suositus niillä luvuilla, jotka yllä ovat.
      """
        .trimIndent()

    val UPCOMING_TASK =
      """
      ## Tehtävä
      Vastaa kolmeen kysymykseen yhtenäisenä tekstinä:
      1. Miltä palautuminen näyttää juuri nyt?
      2. Kannattaako harjoitus tehdä suunnitellusti, keventää tehoa, lyhentää kestoa, vai jättää
         kokonaan väliin?
      3. Perustele niillä luvuilla, jotka yllä ovat.
      """
        .trimIndent()

    /**
     * The three things the answer must not do.
     *
     * The first is ADR-005 in one sentence: this app has no mechanism to act on a proposed plan
     * edit, so an answer that reads like one would be offering something the app cannot deliver.
     * The second keeps the model inside the data — the same discipline the deterministic readiness
     * rule follows, applied to something that could otherwise invent freely.
     *
     * The third is **a hard word count, and it is there because of what happened without one.**
     * Asked for "2–4 kappaletta", all three providers wrote something that read well on a laptop
     * and was far too long on a phone — which is the only screen this app has. The instruction is
     * therefore a number rather than an adjective: "lyhyt" is a word every model interprets against
     * its own defaults, where 110 words is the same length for all of them. Naming the *screen* as
     * the reason is deliberate too — it gives the model something to reason about when trimming,
     * rather than a limit to obey blindly.
     */
    val GUARDRAILS =
      """

      ## Rajoitteet
      - Vastaus luetaan puhelimen ruudulta. Enintään 110 sanaa, 2–3 lyhyttä kappaletta.
        Mieluummin liian lyhyt kuin liian pitkä.
      - Älä ehdota muutoksia harjoitusohjelmaan kalenterissa; sovellus ei voi toteuttaa niitä.
        Anna arvio ja suositus, ei suunnitelmaa.
      - Älä keksi lukuja, joita ei ole yllä. Jos jokin tieto puuttuu, sano se.
      - Vastaa suomeksi, ilman otsikoita ja listamerkkejä — pelkkää tekstiä.
      """
        .trimIndent()
  }
}
