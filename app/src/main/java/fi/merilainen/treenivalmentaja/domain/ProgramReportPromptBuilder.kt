package fi.merilainen.treenivalmentaja.domain

import java.util.Locale
import kotlin.math.abs

/** Which of the two whole-programme reports is being asked for. */
enum class ProgramReportKind {
  /** Mid-plan: how it is going, and does the rest of it still fit. */
  INTERIM,

  /** The plan is over: what changed, and what the next block should emphasise. */
  FINAL,
}

/**
 * The whole-programme report's prompt, rendered from [TrainingProgramAnalysis].
 *
 * Separate from [AnalysisPromptBuilder] rather than a third method on it, because the two ask for
 * opposite things. A session analysis is **110 words with no headings**, read at a glance beside
 * the workout it is about. A programme report is a document: it is opened deliberately, scrolled,
 * and expected to separate what the data says from what the coach thinks of it. Sharing the
 * guardrails would have meant weakening the ones that make the short answer short.
 *
 * What it does share is the discipline: every number here was computed by the app, missing figures
 * are omitted rather than zeroed, and a comparison the data could not support is reported as one
 * that could not be made — never quietly left out for the model to explain.
 */
class ProgramReportPromptBuilder {

  fun build(analysis: TrainingProgramAnalysis, kind: ProgramReportKind): String = buildString {
    appendLine(ROLE)
    appendLine()
    appendLine(
      when (kind) {
        ProgramReportKind.INTERIM -> "Tee väliraportti tästä harjoitusohjelmasta."
        ProgramReportKind.FINAL -> "Tee loppuraportti tästä päättyneestä harjoitusohjelmasta."
      }
    )
    appendLine()

    appendIdentity(analysis)
    appendAdherence(analysis.adherence)
    appendWeeks(analysis.weeks)
    appendRuns(analysis.runs)
    appendRunTrends(analysis)
    appendZoneSplits(analysis.zoneSplits)
    appendStrength(analysis.strength)
    appendFeedback(analysis.feedback)
    appendRecovery(analysis.recovery)
    appendChanges(analysis.changes)
    if (kind == ProgramReportKind.INTERIM) appendRemaining(analysis.remaining)

    appendLine(if (kind == ProgramReportKind.INTERIM) INTERIM_TASK else FINAL_TASK)
    append(GUARDRAILS)
  }

  // ------------------------------------------------------------------------------ the sections

  private fun StringBuilder.appendIdentity(analysis: TrainingProgramAnalysis) {
    val id = analysis.identity
    appendLine("## Ohjelma")
    appendLine("- Nimi: ${id.name}")
    appendLine("- Kesto: ${id.startDate} – ${id.endDate} (${id.weekCount} viikkoa)")
    appendLine(
      when (val phase = analysis.phase) {
        is ProgramPhase.InProgress -> "- Tilanne ${analysis.asOf}: kesken, viikko ${phase.week}/${phase.ofWeeks}"
        ProgramPhase.Finished -> "- Tilanne ${analysis.asOf}: päättynyt"
      }
    )
    // The plan author's own words, quoted rather than summarised: the goals are the thing the
    // report judges everything else against, and a paraphrase of them would move the goalposts.
    id.goals?.let {
      appendLine("- Ohjelman oma kuvaus ja tavoitteet: $it")
    }
    appendLine()
  }

  private fun StringBuilder.appendAdherence(adherence: ProgramAdherence) {
    appendLine("## Toteutuminen")
    appendLine("- Harjoituksia ohjelmassa: ${adherence.planned}")
    appendLine("- Tehty: ${adherence.completed}")
    if (adherence.skipped > 0) appendLine("- Ohitettu: ${adherence.skipped}")
    if (adherence.interrupted > 0) appendLine("- Keskeytetty: ${adherence.interrupted}")
    if (adherence.stillOpen > 0) appendLine("- Vielä tekemättä: ${adherence.stillOpen}")
    if (adherence.rescheduled > 0) appendLine("- Siirretty toiselle päivälle: ${adherence.rescheduled}")
    if (adherence.cancelled > 0) appendLine("- Poistettu ohjelmasta: ${adherence.cancelled}")
    adherence.completionRate?.let {
      appendLine(
        "- Toteutumisprosentti: $it % (tehdyt suhteessa ratkenneisiin; siirretyt ja poistetut " +
          "eivät ole mukana kummassakaan)"
      )
    }
    adherence.byType.entries
      .sortedBy { it.key.ordinal }
      .forEach { (type, counts) ->
        val parts = buildList {
          add("tehty ${counts.completed}")
          if (counts.skipped > 0) add("ohitettu ${counts.skipped}")
          if (counts.interrupted > 0) add("keskeytetty ${counts.interrupted}")
          if (counts.open > 0) add("tekemättä ${counts.open}")
        }
        appendLine("- ${type.title}: ${parts.joinToString(", ")}")
      }
    appendLine()
  }

  private fun StringBuilder.appendWeeks(weeks: List<ProgramWeek>) {
    if (weeks.isEmpty()) return
    appendLine("## Viikot")
    weeks.forEach { week ->
      val parts = buildList {
        add("suunniteltu ${week.planned}")
        add("tehty ${week.completed}")
        if (week.skipped > 0) add("ohitettu ${week.skipped}")
        if (week.interrupted > 0) add("keskeytetty ${week.interrupted}")
        if (week.open > 0) add("tekemättä ${week.open}")
        week.runKm?.let { add("juostu ${km(it)}") }
        week.minutes?.let { add("kesto yhteensä $it min") }
        week.trainingLoad?.let { add("kuormitus $it") }
        week.meanRpe?.let { add("RPE keskimäärin ${decimal(it)}") }
      }
      appendLine("- Viikko ${week.week}: ${parts.joinToString(", ")}")
    }
    appendLine()
  }

  /**
   * Every completed run, one line each.
   *
   * The one place in this prompt where nothing is aggregated. Two runs eight weeks apart are the
   * whole evidence for whether the fitness moved, and a weekly mean destroys exactly the comparison
   * the report exists to make. A line is about fifteen tokens; a hundred runs is affordable.
   */
  private fun StringBuilder.appendRuns(runs: List<ProgramRun>) {
    if (runs.isEmpty()) return
    appendLine("## Juoksut, vanhimmasta uusimpaan")
    runs.forEach { run ->
      val head = buildString {
        append("- ${run.date} (vk ${run.week}")
        run.plannedIntensity?.let { append(", suunniteltu teho ${it.title.lowercase(FINNISH)}") }
        append(")")
      }
      val parts = buildList {
        run.distanceKm?.let { add(km(it)) }
        run.durationSec?.let { add(it.formatDuration()) }
        run.paceSecPerKm?.let { add("tahti ${pace(it)}") }
        run.avgHeartRate?.let { hr ->
          add(run.maxHeartRate?.let { "syke $hr, max $it" } ?: "syke $hr")
        }
        run.elevationGainMeters?.let { add("nousu $it m") }
        run.trainingLoad?.let { add("kuormitus $it") }
        run.trimp?.let { add("TRIMP ${decimal(it)}") }
        run.rpe?.let { add("RPE $it/10") }
        run.feel?.let { add("tuntui: $it") }
      }
      appendLine(if (parts.isEmpty()) head else "$head: ${parts.joinToString(", ")}")
    }
    appendLine()
  }

  /**
   * The comparison, already made.
   *
   * The model is handed the deltas rather than the job of finding them, and — just as importantly —
   * it is handed the groups where no delta could be computed. A silence the model has to explain is
   * a silence it will explain wrongly.
   */
  private fun StringBuilder.appendRunTrends(analysis: TrainingProgramAnalysis) {
    if (analysis.runTrends.isEmpty() && analysis.runGroupsTooSmall.isEmpty()) return
    appendLine("## Juoksujen kehitys (sovelluksen laskema)")
    analysis.runTrends.forEach { trend ->
      appendLine("- ${trend.intensity?.title ?: "Teho ei tiedossa"}:")
      appendLine(
        "  ensimmäiset ${trend.earlyCount} juoksua vs. viimeiset ${trend.lateCount} juoksua"
      )
      line(trend.earlyPaceSecPerKm, trend.latePaceSecPerKm) { early, late ->
        "  tahti ${pace(early)} → ${pace(late)} (${signedSeconds(trend.paceDeltaSec)})"
      }
      line(trend.earlyAvgHeartRate, trend.lateAvgHeartRate) { early, late ->
        "  keskisyke $early → $late (${signed(trend.heartRateDelta)})"
      }
      line(trend.earlyMeanKm, trend.lateMeanKm) { early, late ->
        "  matka keskimäärin ${km(early)} → ${km(late)} (${signedKm(trend.distanceDeltaKm)})"
      }
    }
    analysis.runGroupsTooSmall.forEach { (intensity, count) ->
      appendLine(
        "- ${intensity?.title ?: "Teho ei tiedossa"}: $count juoksua — liian vähän vertailuun, " +
          "kehitystä ei ole laskettu"
      )
    }
    appendLine(
      "- Vertailu tehdään vain saman suunnitellun tehon sisällä, ja vain jos molemmilla puolilla " +
        "on vähintään $MINIMUM_RUNS_PER_HALF juoksua. Negatiivinen tahtimuutos tarkoittaa " +
        "nopeampaa, negatiivinen sykemuutos matalampaa sykettä."
    )
    appendLine()
  }

  /**
   * Where the effort actually went, by planned intensity.
   *
   * **The section that answers "were the easy days easy".** The run lines above carry an average
   * heart rate each, and an average cannot distinguish eight easy runs from six easy ones and two
   * that were raced. This can, and it is the one thing in the report that checks the *plan* against
   * the *execution* rather than the execution against itself.
   *
   * Aggregated per group rather than written per run, which is what makes it affordable: five lines
   * for a whole intensity group against five for every run in it.
   */
  private fun StringBuilder.appendZoneSplits(splits: List<ProgramZoneSplit>) {
    if (splits.isEmpty()) return
    appendLine("## Sykealueiden jakauma suunnitellun tehon mukaan (sovelluksen laskema)")
    splits.forEach { split ->
      appendLine(
        "- ${split.intensity?.title ?: "Teho ei tiedossa"} (${split.runs} juoksua, joissa " +
          "sykealuetiedot):"
      )
      split.zones.forEach { zone ->
        val share = split.percentOf(zone)?.let { " ($it %)" }.orEmpty()
        appendLine("  ${zone.label}: ${zone.seconds.formatDuration()}$share")
      }
    }
    appendLine(
      "- Mukana ovat vain ne juoksut, joista sykealuetiedot löytyivät. Sykealueiden rajat on " +
        "merkitty vain, jos ne pysyivät samoina koko jakson ajan."
    )
    appendLine()
  }

  private fun StringBuilder.appendStrength(strength: ProgramStrength?) {
    if (strength == null) return
    appendLine("## Voima- ja lihaskuntoharjoittelu")
    appendLine("- Tehty: ${strength.completed}")
    if (strength.skipped > 0) appendLine("- Ohitettu: ${strength.skipped}")
    if (strength.interrupted > 0) appendLine("- Keskeytetty: ${strength.interrupted}")
    if (strength.completed > 0) {
      appendLine("- Kaikki liikkeet kuitattu tehdyksi: ${strength.fullyTickedOff} / ${strength.completed}")
    }
    strength.meanDurationMin?.let { appendLine("- Kesto keskimäärin: $it min") }
    if (strength.recurringMovements.isNotEmpty()) {
      appendLine("- Toistuvat liikkeet: ${strength.recurringMovements.joinToString(", ")}")
    }
    line(strength.earlyMeanRpe, strength.lateMeanRpe) { early, late ->
      "- Koettu rasittavuus alussa ${decimal(early)} → lopussa ${decimal(late)}"
    }
    appendLine()
  }

  /**
   * The subjective half.
   *
   * Rendered beside the measurements rather than after the recommendation, because the case worth
   * catching is the one where they disagree: pace and heart rate improving while every session is
   * still reported as heavy is a finding, and a report that reads only the numbers will call that
   * progress and stop.
   */
  private fun StringBuilder.appendFeedback(feedback: ProgramFeedback) {
    if (feedback.ratedSessions == 0 && feedback.feelCounts.isEmpty()) return
    appendLine("## Käyttäjän oma palaute")
    if (feedback.ratedSessions > 0) {
      appendLine("- Arvioituja harjoituksia: ${feedback.ratedSessions}")
    }
    feedback.meanRpe?.let { appendLine("- Koettu rasittavuus (RPE) keskimäärin: ${decimal(it)} / 10") }
    line(feedback.earlyMeanRpe, feedback.lateMeanRpe) { early, late ->
      "- RPE alkupuolella ${decimal(early)} → loppupuolella ${decimal(late)}"
    }
    if (feedback.feelCounts.isNotEmpty()) {
      appendLine("- Miltä tuntui: ${feedback.feelCounts.joinToString(", ") { "${it.first} ${it.second} kertaa" }}")
    }
    if (feedback.earlyFeelCounts.isNotEmpty() && feedback.lateFeelCounts.isNotEmpty()) {
      appendLine("  alkupuolella: ${feedback.earlyFeelCounts.joinToString(", ") { "${it.first} ${it.second}" }}")
      appendLine("  loppupuolella: ${feedback.lateFeelCounts.joinToString(", ") { "${it.first} ${it.second}" }}")
    }
    // Said because the split can move for a reason that has nothing to do with fitness: a block
    // that swaps a strength session for a run changes the mix, and the mean follows it. The per-run
    // RPE above and the strength section's own trend are the type-specific readings.
    appendLine(
      "- Nämä luvut kattavat kaikki harjoitustyypit yhdessä, joten alku- ja loppupuolen ero voi " +
        "johtua myös lajijakauman muutoksesta. Lajikohtaiset arviot ovat juoksujen riveillä ja " +
        "voimaosiossa."
    )
    appendLine()
  }

  private fun StringBuilder.appendRecovery(recovery: ProgramRecoveryTrend?) {
    if (recovery == null) return
    appendLine("## Palautumisen kehitys ohjelman aikana (Oura, ${recovery.days} mitattua aamua)")
    line(recovery.earlyMeanHrvMs, recovery.lateMeanHrvMs) { early, late ->
      "- HRV keskimäärin $early ms → $late ms"
    }
    line(recovery.earlyMeanRestingHr, recovery.lateMeanRestingHr) { early, late ->
      "- Leposyke keskimäärin $early → $late"
    }
    line(recovery.earlyMeanReadiness, recovery.lateMeanReadiness) { early, late ->
      "- Palautumislukema keskimäärin $early → $late"
    }
    appendLine()
  }

  private fun StringBuilder.appendChanges(changes: List<ProgramChange>) {
    if (changes.isEmpty()) return
    appendLine("## Muutokset alkuperäiseen ohjelmaan")
    changes.forEach { change ->
      appendLine("- ${change.date} (vk ${change.week}, ${change.type.title}): ${change.kind.title}")
    }
    appendLine()
  }

  private fun StringBuilder.appendRemaining(remaining: ProgramRemaining?) {
    if (remaining == null) return
    appendLine("## Ohjelmasta jäljellä")
    appendLine("- ${remaining.sessions} harjoitusta, ${remaining.weeks} viikolla, viimeinen ${remaining.lastDate}")
    remaining.byType.entries
      .sortedBy { it.key.ordinal }
      .forEach { (type, count) -> appendLine("- ${type.title}: $count") }
    remaining.plannedRunKm?.let { appendLine("- Suunniteltuja juoksukilometrejä jäljellä: ${km(it)}") }
    appendLine()
  }

  // ------------------------------------------------------------------------------ formatting

  /** Writes a line only when both halves of a comparison exist. Absent is not zero, here too. */
  private inline fun <T : Any> StringBuilder.line(early: T?, late: T?, text: (T, T) -> String) {
    if (early != null && late != null) appendLine(text(early, late))
  }

  private fun signed(value: Int?): String =
    when {
      value == null -> "ei laskettavissa"
      value > 0 -> "+$value"
      else -> "$value"
    }

  private fun signedSeconds(value: Int?): String =
    when {
      value == null -> "ei laskettavissa"
      value == 0 -> "ei muutosta"
      value < 0 -> "${abs(value)} s/km nopeampi"
      else -> "$value s/km hitaampi"
    }

  private fun signedKm(value: Double?): String =
    when {
      value == null -> "ei laskettavissa"
      value > 0 -> "+${km(value)}"
      else -> "-${km(abs(value))}"
    }

  private fun pace(secPerKm: Int): String = "%d:%02d /km".format(secPerKm / 60, secPerKm % 60)

  private fun km(value: Double): String = String.format(FINNISH, "%.2f km", value)

  private fun decimal(value: Double): String = String.format(FINNISH, "%.1f", value)

  companion object {

    private val FINNISH: Locale = Locale("fi", "FI")

    val ROLE =
      """
      Olet kokenut kestävyys- ja voimaharjoittelun valmentaja. Puhut suomea.
      Saat alta yhden harjoitusohjelman kaikki olennaiset tiedot: ohjelman rakenteen ja tavoitteet,
      toteutumisen, viikkokohtaiset yhteenvedot, jokaisen juoksun mittaustiedot, sovelluksen
      valmiiksi laskemat kehitysvertailut, voimaharjoittelun yhteenvedon, käyttäjän oman palautteen
      ja palautumisdatan.

      Kaikki luvut ja vertailut on laskettu sovelluksessa. Älä laske niitä uudelleen äläkä keksi
      lukuja, joita ei ole annettu. Puuttuva rivi tarkoittaa, ettei mittausta ole — ei nollaa.
      """
        .trimIndent()

    /**
     * The eight questions, in the order they were asked for.
     *
     * The three-section shape is the point of the whole feature: a coach who cannot tell you which
     * of their sentences is a measurement, which is a reading and which is advice is producing
     * prose rather than coaching. Question 5 is named explicitly because it is the one a numbers-
     * only report always gets wrong — improving figures beside a person who keeps saying the
     * sessions are too hard is a finding, not a success.
     */
    val INTERIM_TASK =
      """
      ## Tehtävä
      Kirjoita väliraportti, joka vastaa näihin kysymyksiin:
      1. Miten ohjelma on toteutunut tähän mennessä?
      2. Miten käyttäjä on kehittynyt ohjelman alusta tähän päivään?
      3. Missä asioissa kehitys näkyy selvimmin?
      4. Onko harjoittelussa ollut ongelmia, epätasaisuutta tai puutteita?
      5. Miten käyttäjän oma kokemus vastaa mitattua dataa? Jos ne ovat ristiriidassa — esimerkiksi
         mittarit paranevat mutta harjoitukset tuntuvat jatkuvasti raskailta — sano se.
      6. Onko nykyinen kuormitus ja eteneminen järkevää?
      7. Miltä ohjelman jäljellä oleva osa näyttää suhteessa tähänastiseen kehitykseen?
      8. Kannattaako ohjelmaa jatkaa sellaisenaan vai olisiko jotain syytä muuttaa?

      Jäsennä vastaus kolmeen otsikkoon tässä järjestyksessä, äläkä lisää neljättä:
      ## Näin ohjelma on toteutunut
      ## Tulkinta
      ## Suositus
      """
        .trimIndent()

    val FINAL_TASK =
      """
      ## Tehtävä
      Kirjoita loppuraportti päättyneestä ohjelmasta. Käy läpi ainakin: mistä lähtötilanteesta
      ohjelma alkoi, mitä käyttäjä teki sen aikana, kuinka hyvin ohjelma toteutui, miten
      suorituskyky muuttui, missä kehitystä ei tapahtunut tai data ei riitä johtopäätökseen,
      käyttäjän oman kokemuksen kehitys, harjoittelun säännöllisyys, kuormituksen kehitys,
      ohitettujen ja keskeytettyjen harjoitusten merkitys, mikä ohjelmassa näytti sopivan
      käyttäjälle hyvin ja mikä ei.

      Jäsennä vastaus näihin otsikoihin tässä järjestyksessä, äläkä lisää muita:
      ## Näin ohjelma toteutui
      ## Tulkinta
      ## Alussa → lopussa
      ## Suositus seuraavalle jaksolle

      "Alussa → lopussa" on lyhyt: 2–4 konkreettista muutosta, jokainen yhdellä rivillä ja
      kumpikin luku mainittuna. Ota mukaan vain ne, jotka data oikeasti osoittaa.

      "Suositus seuraavalle jaksolle" on muutama painopiste seuraavalle noin 8 viikon jaksolle —
      ei valmis ohjelma, ei viikkokohtaista suunnitelmaa. Jos edellinen ohjelma toteutui huonosti,
      tuntui jatkuvasti liian raskaalta tai kehitystä ei näy, älä ehdota kovempaa jaksoa vain siksi
      että edellinen päättyi.
      """
        .trimIndent()

    /**
     * Deliberately not [AnalysisPromptBuilder.GUARDRAILS], and the differences are the design.
     *
     * Headings are **required** here where the session analysis forbids them, and the word budget
     * is a document's rather than a glance's. What carries over unchanged is the ban on inventing
     * figures, and one rule that matters more here than anywhere else: an absent comparison must be
     * reported as absent. The aggregate deliberately tells the model which groups were too small to
     * compare, and this is the line that stops it treating that as an invitation.
     */
    val GUARDRAILS =
      """

      ## Rajoitteet
      - Raportti luetaan puhelimen ruudulta, mutta se on dokumentti eikä vilkaisu: enintään noin
        450 sanaa. Käytä yllä pyydettyjä otsikoita.
      - Erota selvästi mitattu fakta, oma tulkintasi ja suositus. Tulkinnassa saat arvailla varovasti,
        faktaosuudessa et.
      - Jos data ei osoita kehitystä, sano että se ei osoita. Älä keksi kehitystä.
      - Jos jokin vertailu on merkitty liian vähän dataa sisältäväksi, älä tee siitä johtopäätöstä
        etkä selitä sen puuttumista.
      - Älä luettele annettuja lukuja takaisin sellaisenaan. Yksittäisen luvun saa mainita, kun se
        perustelee väitteen — etenkin "Alussa → lopussa" -osiossa, jossa luvut ovat pointti.
      - Älä ehdota yksittäisiä kalenterimuutoksia; sovellus ei toteuta niitä tästä raportista.
      - Vastaa suomeksi.
      """
        .trimIndent()
  }
}
