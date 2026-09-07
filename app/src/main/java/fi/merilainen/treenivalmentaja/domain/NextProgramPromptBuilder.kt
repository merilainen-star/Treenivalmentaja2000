package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import java.util.Locale

/**
 * Asks the model for the next training block, as plan JSON the existing importer can read.
 *
 * **The output is a document, not prose.** It goes to `PlanJson.parse` → `PlanValidator.validate` →
 * `TrainingRepository.importPlan`, the same path a hand-written plan takes, so a plan the model
 * gets wrong is rejected by the same validator that rejects a plan a human gets wrong. There is no
 * second persistence path and no second set of rules — which is the only reason this feature is
 * affordable at all. See `docs/PLAN_SCHEMA.md`.
 *
 * The context handed over is the previous programme's aggregate, not its raw sessions: what the
 * next block needs to know is where the person *ended up*, and that is what the trends say. The
 * final report is included when there is one, because it is the reading the person has just read
 * and agreed to act on.
 */
class NextProgramPromptBuilder {

  fun build(
    analysis: TrainingProgramAnalysis,
    request: NextProgramRequest,
    startDate: LocalDate,
    timeZone: String,
    /** The loppuraportti the model just wrote, when the person has one open. */
    finalReport: String? = null,
    constraints: String = "",
  ): String = buildString {
    appendLine(ROLE)
    appendLine()
    appendLine(
      "Suunnittele seuraava ${request.weeks} viikon harjoitusjakso, joka alkaa $startDate."
    )
    appendLine()

    appendPreviousLevel(analysis)
    appendFeedback(analysis, request)
    appendGoal(request)
    if (constraints.isNotBlank()) {
      appendLine("## Käyttäjän pysyvät rajoitteet")
      appendLine(constraints.trim())
      appendLine("Noudata näitä myös uudessa ohjelmassa. Käyttäjä tarkistaa ne ennen hyväksyntää.")
      appendLine()
    }
    finalReport?.trim()?.takeIf { it.isNotEmpty() }?.let {
      appendLine("## Juuri kirjoittamasi loppuraportti edellisestä jaksosta")
      appendLine(it)
      appendLine()
    }
    appendLine(rules(request, startDate, timeZone))
    append(OUTPUT)
  }

  // ------------------------------------------------------------------------------ context

  /**
   * Where the person ended up, which is where the next plan starts.
   *
   * The **late** halves of the trends lead, and are labelled as the current level, because that is
   * the whole point: a plan that starts from a generic baseline throws away the block that was just
   * done. The early halves are there so the model can see the direction as well as the position.
   */
  private fun StringBuilder.appendPreviousLevel(analysis: TrainingProgramAnalysis) {
    appendLine("## Edellinen jakso ja nykyinen taso")
    appendLine("- Ohjelma: ${analysis.identity.name}, ${analysis.identity.weekCount} viikkoa")
    analysis.identity.goals?.let { appendLine("- Edellisen jakson tavoitteet: $it") }
    appendLine("- Harjoituksia: ${analysis.adherence.planned}, tehty ${analysis.adherence.completed}")
    analysis.adherence.completionRate?.let { appendLine("- Toteutumisprosentti: $it %") }
    if (analysis.adherence.skipped > 0) appendLine("- Ohitettuja: ${analysis.adherence.skipped}")
    if (analysis.adherence.interrupted > 0) {
      appendLine("- Keskeytettyjä: ${analysis.adherence.interrupted}")
    }

    val weeklyKm = analysis.weeks.mapNotNull { it.runKm }
    if (weeklyKm.isNotEmpty()) {
      appendLine(
        "- Toteutunut juoksu viikossa: keskimäärin ${km(weeklyKm.average())}, " +
          "suurin viikko ${km(weeklyKm.max())}"
      )
    }
    analysis.runs.mapNotNull { it.distanceKm }.maxOrNull()?.let {
      appendLine("- Pisin yksittäinen juoksu: ${km(it)}")
    }

    analysis.runTrends.forEach { trend ->
      val level = buildList {
        trend.latePaceSecPerKm?.let { add("tahti ${pace(it)}") }
        trend.lateAvgHeartRate?.let { add("keskisyke $it") }
        trend.lateMeanKm?.let { add("matka ${km(it)}") }
      }
      if (level.isNotEmpty()) {
        appendLine(
          "- ${trend.intensity?.title ?: "Teho ei tiedossa"} -juoksujen taso jakson lopussa: " +
            level.joinToString(", ")
        )
      }
      val start = buildList {
        trend.earlyPaceSecPerKm?.let { add("tahti ${pace(it)}") }
        trend.earlyAvgHeartRate?.let { add("keskisyke $it") }
      }
      if (start.isNotEmpty()) appendLine("  (jakson alussa: ${start.joinToString(", ")})")
    }
    analysis.runGroupsTooSmall.forEach { (intensity, count) ->
      appendLine(
        "- ${intensity?.title ?: "Teho ei tiedossa"}: vain $count juoksua, tasoa ei voi päätellä"
      )
    }

    analysis.strength?.let { strength ->
      appendLine(
        "- Voimaharjoittelu: ${strength.completed} tehtyä" +
          (strength.meanDurationMin?.let { ", keskimäärin $it min" } ?: "")
      )
      if (strength.recurringMovements.isNotEmpty()) {
        appendLine("  toistuneet liikkeet: ${strength.recurringMovements.joinToString(", ")}")
      }
    }

    analysis.recovery?.let { recovery ->
      val parts = buildList {
        if (recovery.earlyMeanHrvMs != null && recovery.lateMeanHrvMs != null) {
          add("HRV ${recovery.earlyMeanHrvMs} → ${recovery.lateMeanHrvMs} ms")
        }
        if (recovery.earlyMeanRestingHr != null && recovery.lateMeanRestingHr != null) {
          add("leposyke ${recovery.earlyMeanRestingHr} → ${recovery.lateMeanRestingHr}")
        }
      }
      if (parts.isNotEmpty()) appendLine("- Palautuminen jakson aikana: ${parts.joinToString(", ")}")
    }
    appendLine()
  }

  private fun StringBuilder.appendFeedback(
    analysis: TrainingProgramAnalysis,
    request: NextProgramRequest,
  ) {
    appendLine("## Käyttäjän kokemus edellisestä jaksosta")
    analysis.feedback.meanRpe?.let { appendLine("- Koettu rasittavuus keskimäärin ${decimal(it)} / 10") }
    if (analysis.feedback.earlyMeanRpe != null && analysis.feedback.lateMeanRpe != null) {
      appendLine(
        "- Rasittavuus alkupuolella ${decimal(analysis.feedback.earlyMeanRpe)} → " +
          "loppupuolella ${decimal(analysis.feedback.lateMeanRpe)}"
      )
    }
    if (analysis.feedback.feelCounts.isNotEmpty()) {
      appendLine(
        "- Miltä treenit tuntuivat: " +
          analysis.feedback.feelCounts.joinToString(", ") { "${it.first} ${it.second} kertaa" }
      )
    }
    // The one thing the measurements cannot supply, and the reason it is asked at all.
    request.fit?.let {
      appendLine("- **Käyttäjän oma arvio koko jaksosta: ${it.title.lowercase(FINNISH)}.** ${it.hint}")
    }
      ?: appendLine("- Käyttäjä ei antanut kokonaisarviota jaksosta.")
    appendLine()
  }

  private fun StringBuilder.appendGoal(request: NextProgramRequest) {
    appendLine("## Seuraavan jakson tavoite")
    appendLine("- Valittu painotus: ${request.goal.title}")
    request.goal.hint.takeIf { it.isNotEmpty() }?.let { appendLine("  $it") }
    request.ownGoal?.trim()?.takeIf { it.isNotEmpty() }?.let {
      appendLine("- Käyttäjän oma tavoite, omin sanoin: $it")
    }
    appendLine()
  }

  // ------------------------------------------------------------------------------ the rules

  /**
   * The schema, trimmed to what a plan actually needs.
   *
   * Not the whole of `PLAN_SCHEMA.md`: that document explains the format to a person and runs to
   * three hundred lines, most of it fields this prompt should not encourage. What is here is the
   * required shape plus the optional fields worth using, and every rule the validator enforces —
   * because a rule the model cannot see is a rejection it cannot avoid.
   */
  private fun rules(request: NextProgramRequest, startDate: LocalDate, timeZone: String): String =
    """
    ## Kaksi sääntöä, jotka ohittavat kaiken muun
    1. Jakso jatkuu siitä tasosta johon käyttäjä edellisen jakson lopussa pääsi. Älä aloita
       geneeriseltä aloittelijatasolta, ja älä toista edellisen jakson alkua.
    2. Edellisen jakson päättyminen ei ole peruste vaikeuttaa. Jos toteutumisprosentti oli heikko,
       koettu rasittavuus nousi ilman vauhdin paranemista, tai käyttäjä arvioi jakson liian
       raskaaksi, pidä kuorma ennallaan tai kevennä sitä.

    ## Muoto
    Palauta **pelkkä JSON-dokumentti** Treenivalmentaja Training Plan Schema v1 -muodossa.
    Ei selityksiä, ei koodilohkon merkkejä, ei tekstiä ennen tai jälkeen.

    Rakenne:
    {
      "schemaVersion": 1,
      "plan": {
        "id": "yksilöllinen-tunniste",
        "name": "Jakson nimi",
        "timeZone": "$timeZone",
        "startDate": "$startDate",
        "description": "Mihin tämä jakso tähtää, 1–3 lausetta."
      },
      "weeks": [
        {
          "weekNumber": 1,
          "focus": "Viikon painotus",
          "sessions": [
            {
              "id": "yksilöllinen-tunniste",
              "type": "RUNNING",
              "date": "$startDate",
              "time": "17:30",
              "durationMin": 45,
              "distanceKm": 8.0,
              "intensity": "EASY",
              "targetPace": "6:00-6:15",
              "description": "Mitä tehdään ja miksi."
            }
          ]
        }
      ]
    }

    Pakolliset ja tarkistettavat säännöt — validoija hylkää dokumentin jos näitä rikotaan:
    - `schemaVersion` on tasan 1.
    - `plan.id` ja jokainen `session.id` ovat ei-tyhjiä ja **koko dokumentissa yksilöllisiä**.
      Käytä `plan.id`:ssä alkupäivää, esim. "plan-$startDate".
    - `plan.timeZone` on "$timeZone", `plan.startDate` on "$startDate".
    - Viikkoja on ${request.weeks}, `weekNumber` 1..${request.weeks}, jokainen kerran.
    - Jokaisella harjoituksella on `type` (RUNNING, STRENGTH tai SKIING), `date` muodossa
      YYYY-MM-DD (aikaisintaan $startDate) ja `time` muodossa HH:mm.
    - Jokaisella harjoituksella on vähintään yksi seuraavista: `durationMin`, `distanceKm` tai
      ei-tyhjä `exercises`.
    - `intensity` on EASY, MODERATE, HARD tai MAX, jos se on mukana.
    - Voimaharjoituksen jokaisella liikkeellä on `name` ja vähintään `reps` tai `durationSec`.
      Pidoissa (lankku, riipunta) käytetään `durationSec`, ei `reps`.
      Puolittaisissa liikkeissä `perSide: true`.
    - Lepopäivät jätetään pois: viikossa on vain ne päivät joilla on harjoitus.

    Sisällöstä:
    - Kirjoita `description`-kentät suomeksi, lyhyesti ja konkreettisesti.
    - Anna juoksuille `distanceKm` ja `targetPace`, jotta kehitystä voi seurata seuraavallakin
      kerralla. Käytä tahtina sitä tasoa jolla käyttäjä oikeasti juoksee.
    - Anna voimaharjoituksille `exercises` toistoineen — pelkkä kuvaus ei riitä ohjattuun tilaan.
    - Pidä viikkorakenne toistettavana: samat viikonpäivät läpi jakson, ellei ole syytä poiketa.
    """
      .trimIndent()

  // ------------------------------------------------------------------------------ formatting

  private fun pace(secPerKm: Int): String = "%d:%02d /km".format(secPerKm / 60, secPerKm % 60)

  private fun km(value: Double): String = String.format(FINNISH, "%.1f km", value)

  private fun decimal(value: Double): String = String.format(FINNISH, "%.1f", value)

  companion object {

    private val FINNISH: Locale = Locale("fi", "FI")

    val ROLE =
      """
      Olet kokenut kestävyys- ja voimaharjoittelun valmentaja, joka laatii harjoitusohjelmia.
      Saat alta yhteenvedon juuri päättyneestä harjoitusjaksosta: mihin tasoon käyttäjä pääsi,
      miten jakso toteutui, miltä se tuntui, ja mitä käyttäjä haluaa painottaa seuraavaksi.

      Kaikki luvut on laskettu sovelluksessa. Älä keksi lukuja joita ei ole annettu.
      """
        .trimIndent()

    /**
     * The last word, and it is about the channel rather than the content.
     *
     * Repeated after the schema because it is the instruction most often lost: every provider's
     * default is to wrap JSON in a fenced block and introduce it, and the parser reads from the
     * first `{` to the last `}` — which survives a fence but not a model that decides to show two
     * examples.
     */
    val OUTPUT =
      """

      ## Vastaus
      Vastaa pelkällä JSON-dokumentilla. Ensimmäinen merkki on { ja viimeinen on }.
      """
        .trimIndent()
  }
}
