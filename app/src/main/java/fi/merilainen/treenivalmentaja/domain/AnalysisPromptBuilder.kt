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
  /** Circuit rounds the movement list is repeated for, when the plan said. */
  val plannedRounds: Int? = null,
  /**
   * The movements the plan asked for, in order.
   *
   * Empty for a plan that defines none — those sessions describe their work in [description] and
   * there is nothing structured to render.
   */
  val exercises: List<Exercise> = emptyList(),
  /**
   * What the guided workout recorded: which of those movements were actually ticked off.
   *
   * `null` when nothing was recorded, which is a different fact from nothing being done — a
   * session completed from a screen with no guided list, or one finished before the app recorded
   * this at all. Both render no section rather than an empty one.
   */
  val guided: GuidedProgress? = null,
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
  /**
   * The athlete's training load, with the day it describes.
   *
   * Dated because load decays: a figure without its date cannot be checked for staleness, and the
   * first version of this feature sent a three-day-old fatigue as if it were current.
   */
  val load: DailyTrainingLoad? = null,
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

    appendProgramme(input.exercises, input.plannedRounds)
    appendGuided(input.guided, input.exercises)

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
    appendContributors(
      heading = "## Palautumisen erittely harjoituspäivältä",
      day = input.date,
      recovery = input.recoveryByDay[input.date],
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
    appendContributors(
      heading = "## Tämän päivän palautumisen erittely",
      day = input.date,
      recovery = input.recoveryByDay[input.date],
    )

    // Written only when intervals.icu actually computed both. The pair is what means something, and
    // one alone invites the model to infer the other.
    //
    // **The heading carries the date**, and that is not decoration. Load decays daily, so a figure
    // is only as good as the day it belongs to — and when the series has no row for the session's
    // own day, this says which day it does describe rather than letting an older number pass as
    // today's. That is precisely how the first version went wrong, silently.
    input.load?.let { load ->
      val acute = load.acute
      val chronic = load.chronic
      val balance = load.stressBalance
      if (acute != null && chronic != null && balance != null) {
        appendLine("## Kuormitus (${load.date})")
        appendLine("- Akuutti kuormitus (väsymys): ${decimal(acute)}")
        appendLine("- Krooninen kuormitus (kunto): ${decimal(chronic)}")
        appendLine("- Erotus (TSB): ${decimal(balance)}")
        appendLine()
      }
    }

    appendLine(UPCOMING_TASK)
    append(GUARDRAILS)
  }

  /**
   * The plan's own movements, in order, each with what it asked for.
   *
   * Without this the model was told a duration, an intensity and a free-text description, and had
   * to guess the rest — which is why an answer about a strength session used to say that loads and
   * rounds were unknown. They were in the database the whole time; nothing sent them.
   */
  private fun StringBuilder.appendProgramme(exercises: List<Exercise>, rounds: Int?) {
    if (exercises.isEmpty()) return
    appendLine("## Suunniteltu ohjelma")
    // Only when the plan says more than one. "1 kierros" is noise, and a circuit is the only
    // reason the count is interesting.
    rounds?.takeIf { it > 1 }?.let {
      appendLine("- $it kierrosta, ${exercises.size} liikettä kierroksella")
    }
    exercises.forEach { exercise ->
      val work = exercise.promptPrescription()
      appendLine(if (work.isEmpty()) "- ${exercise.name}" else "- ${exercise.name}: $work")
    }
    appendLine()
  }

  /**
   * What the person ticked off, and — when the counts still line up — which movements those were.
   *
   * **The naming is guarded on purpose.** [progress] carries the shape it was counted against, and
   * "Kevyempi versio" can swap the movement list under a session after the fact. When that shape no
   * longer matches [exercises], the counts are still true and the names are not, so only the counts
   * are written. Naming the wrong movements as done is worse than naming none.
   */
  private fun StringBuilder.appendGuided(progress: GuidedProgress?, exercises: List<Exercise>) {
    if (progress == null || progress.total <= 0) return
    appendLine("## Toteutunut (ohjattu treeni)")

    if (progress.isComplete) {
      appendLine(
        "- Kaikki ${progress.total} liikesuoritusta kuitattiin tehdyksi, ja harjoitus " +
          "kuitattiin valmiiksi."
      )
      // The sentence the whole feature exists for: it turns the programme above from a plan into
      // a record of what happened, so the model stops reporting reps and loads as unknown.
      appendLine(
        "- Ohjelma toteutui suunnitellusti: yllä luetellut toistot, kuormat ja kestot ovat myös " +
          "toteutuneet."
      )
      appendLine()
      return
    }

    appendLine(
      "- ${progress.done} / ${progress.total} liikesuoritusta kuitattiin tehdyksi. Harjoitus " +
        "kuitattiin päättyneeksi ennen kuin lista oli lopussa."
    )
    if (exercises.size == progress.perRound) {
      for (round in 1..progress.rounds) {
        val doneInRound = (progress.done - (round - 1) * progress.perRound)
          .coerceIn(0, progress.perRound)
        val line =
          when (doneInRound) {
            progress.perRound -> "kaikki ${progress.perRound} liikettä tehty"
            0 -> "ei tehty yhtään liikettä"
            else ->
              "tehty ${exercises.take(doneInRound).joinToString(", ") { it.name }}; " +
                "tekemättä ${exercises.drop(doneInRound).joinToString(", ") { it.name }}"
          }
        appendLine(if (progress.rounds > 1) "- Kierros $round: $line" else "- $line")
      }
    }
    appendLine()
  }

  /**
   * One movement's prescription, written for a reader who is being asked to reason about it.
   *
   * Deliberately **not** the screen's `Exercise.prescription()` shorthand (`4 × 10 · 55 kg`). That
   * one is read at a glance between sets, where the units are obvious from context; here rule 2
   * above applies, and every number carries its own.
   */
  private fun Exercise.promptPrescription(): String {
    // Appended only to a prescription that says something. An exercise the plan named and left
    // otherwise blank must render as its name alone, not as a stray "/ puoli".
    fun String.withSide(): String =
      if (isEmpty()) "" else if (perSide == true) "$this / puoli" else this

    // A ramp is spelled out set by set — differing loads are the entire reason a plan writes one.
    setPlan?.takeIf { it.isNotEmpty() }?.let { plan ->
      return plan
        .joinToString(", ") { set ->
          listOfNotNull(
              set.weightKg?.let { kg(it) },
              set.reps?.let { "$it toistoa" },
              set.durationSec?.let { "$it s" },
            )
            .joinToString(" × ")
        }
        .withSide()
    }

    // The range when the plan gave one, rather than the single figure the screen shows. "6–8
    // toistoa" is what the plan actually asks for, and a model reasoning about whether the session
    // was hard enough needs the band, not its lower edge.
    val repsText =
      when {
        repsMin != null && repsMax != null && repsMin != repsMax -> "$repsMin–$repsMax toistoa"
        reps != null -> "$reps toistoa"
        repsMin != null -> "$repsMin toistoa"
        else -> null
      }
    val volume =
      listOfNotNull(sets?.takeIf { it > 1 }?.let { "$it sarjaa" }, repsText, durationSec?.let { "$it s" })
        .joinToString(" × ")
    return listOfNotNull(volume.ifEmpty { null }, weightKg?.let { kg(it) })
      .joinToString(", ")
      .withSide()
  }

  /** 55.0 reads as "55 kg", 17.5 as "17,5 kg" — Finnish decimal comma, no trailing zero. */
  private fun kg(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()} kg" else String.format(FINNISH, "%.1f kg", value)

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

  /**
   * Oura's own breakdown of the score, for **the one day this analysis is about** — never the trend
   * range [appendRecovery] covers. Ten more numbers on every line of a week-long trend would bury it;
   * "why is today's number what it is" is a question about today, not about the whole week.
   *
   * Every contributor is written `n/100`, deliberately unlike [appendRecovery]'s `HRV 61 ms` and
   * `leposyke 52`: these are Oura's 1–100 opinion of those same measurements relative to this
   * athlete's own baseline, not the measurements themselves, and rule 2 exists precisely so the two
   * are never confused for each other. See ADR-014 in `docs/DECISIONS.md`.
   */
  private fun StringBuilder.appendContributors(
    heading: String,
    day: LocalDate,
    recovery: DailyRecovery?,
  ) {
    if (recovery == null) return
    val c = recovery.readinessContributors
    val parts = buildList {
      recovery.activityRecoveryTime?.let { add("palautumisaika (7 vrk) $it/100") }
      c?.previousNight?.let { add("edellinen yö $it/100") }
      c?.sleepBalance?.let { add("unen tasapaino $it/100") }
      c?.sleepRegularity?.let { add("unen säännöllisyys $it/100") }
      c?.hrvBalance?.let { add("HRV-tasapaino $it/100") }
      c?.restingHeartRate?.let { add("leposykkeen pisteytys $it/100") }
      c?.recoveryIndex?.let { add("palautumisindeksi $it/100") }
      c?.previousDayActivity?.let { add("edellisen päivän aktiivisuus $it/100") }
      c?.activityBalance?.let { add("aktiivisuustasapaino $it/100") }
      c?.bodyTemperature?.let { add("kehon lämpötila $it/100") }
    }
    if (parts.isEmpty()) return
    appendLine(heading)
    appendLine("- $day: ${parts.joinToString(", ")}")
    appendLine()
  }

  private fun km(value: Double): String = String.format(FINNISH, "%.2f km", value)

  private fun decimal(value: Double): String = String.format(FINNISH, "%.1f", value)

  companion object {

    private val FINNISH: Locale = Locale("fi", "FI")

    /**
     * A week of mornings around the session — enough to see a direction, short enough to read.
     *
     * Public because the caller has to fetch exactly this range from the database: a ViewModel that
     * queried a different window than the builder renders would either waste rows or, worse, hand
     * over a map that silently lacks the days the prompt asks for.
     */
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
      Ohjatun treenin kuittaukset kertovat, mitkä liikkeet tehtiin. Jos kaikki liikkeet on
      kuitattu, käsittele suunnitellut toistot, kuormat ja kestot toteutuneina äläkä sano, ettei
      niistä ole tietoa. Jos osa jäi kuittaamatta, ne jäivät tekemättä.

      Vastaa kolmeen kysymykseen yhtenäisenä tekstinä:
      1. Miten harjoitus meni suhteessa siihen, mitä oli suunniteltu?
      2. Miltä kuormitus näyttää sen aamun palautumislukemien ja viime päivien kehityksen valossa?
      3. Suositus seuraavaksi: painaako kovempaa, jatkaako suunnitelman mukaan, vai levätäkö.
         Perustele tulkitsemalla dataa — älä luettele lukuja uudelleen, ne näkyvät jo sovelluksessa.
      """
        .trimIndent()

    val UPCOMING_TASK =
      """
      ## Tehtävä
      Vastaa kolmeen kysymykseen yhtenäisenä tekstinä:
      1. Miltä palautuminen näyttää juuri nyt?
      2. Kannattaako harjoitus tehdä suunnitellusti, keventää tehoa, lyhentää kestoa, vai jättää
         kokonaan väliin?
      3. Perustele tulkitsemalla dataa — älä luettele lukuja uudelleen, ne näkyvät jo sovelluksessa.
      """
        .trimIndent()

    /**
     * The four things the answer must not do.
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
     *
     * **The fourth exists because of a real answer, not a hypothetical one:** "Aamun palautuminen
     * oli erittäin hyvä: palautuminen 91, uni 89, HRV 44 ms ja leposyke 48. Tilanne on selvästi
     * kohentunut 28.8. notkahduksesta, jolloin palautuminen oli 67, HRV 20 ms ja leposyke 58."
     * Seven numbers restated verbatim, every one of them already on the screen the user opened
     * this analysis from, out of a 110-word budget meant to hold a verdict and a recommendation.
     * "Perustele ... luvuilla" (the task instructions above) was being read as "list the numbers",
     * which is not the same request as "use them to reason" — so both now say "tulkitsemalla" and
     * this guardrail says the rest. Not "never cite a number": one named to ground a specific claim
     * is still fine, and the rule above it about inventing figures still applies to it. The line
     * being drawn is between reporting the input and interpreting it.
     */
    val GUARDRAILS =
      """

      ## Rajoitteet
      - Vastaus luetaan puhelimen ruudulta. Enintään 110 sanaa, 2–3 lyhyttä kappaletta.
        Mieluummin liian lyhyt kuin liian pitkä.
      - Älä ehdota muutoksia harjoitusohjelmaan kalenterissa; sovellus ei voi toteuttaa niitä.
        Anna arvio ja suositus, ei suunnitelmaa.
      - Älä keksi lukuja, joita ei ole yllä. Jos jokin tieto puuttuu, sano se.
      - Älä listaa annettuja lukuja takaisin käyttäjälle sellaisenaan — ne näkyvät jo sovelluksessa.
        Tulkitse niitä sanallisesti: mitä ne tarkoittavat ja mitä kannattaa tehdä. Yksittäisen
        luvun voi mainita, jos se perustelee suosituksen.
      - Vastaa suomeksi, ilman otsikoita ja listamerkkejä — pelkkää tekstiä.
      """
        .trimIndent()
  }
}
