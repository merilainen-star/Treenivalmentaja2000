package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the person wants the next block to emphasise.
 *
 * [hint] is the sentence handed to the model, and it is deliberately about *emphasis* rather than
 * about numbers: the model has the whole previous programme in front of it and is better placed to
 * pick the loads than a constant in this file is. What the app decides is the direction.
 */
enum class NextProgramGoal(val title: String, val hint: String) {
  BALANCED(
    "Jatka nykyisellä tasapainolla",
    "Säilytä sama lajijakauma ja sama kuormituksen taso kuin edellisessä jaksossa, ja etene maltillisesti.",
  ),
  RUNNING_FITNESS(
    "Parempi juoksukunto",
    "Painota aerobista peruskestävyyttä: enemmän kevyttä juoksua, maltillinen määrän kasvu.",
  ),
  RUNNING_SPEED(
    "Nopeampi juoksuvauhti",
    "Lisää hallittua vauhtiharjoittelua kevyen pohjan päälle. Kovia harjoituksia enintään kaksi viikossa.",
  ),
  RUNNING_DISTANCE(
    "Pidemmät juoksumatkat",
    "Kasvata pisintä lenkkiä asteittain ja pidä muut juoksut kevyinä.",
  ),
  STRENGTH(
    "Voima ja lihaskunto",
    "Painota voima- ja lihaskuntoharjoittelua ja pidä juoksu ylläpitävänä.",
  ),
  WEIGHT(
    "Painonhallinta",
    "Painota säännöllistä, kohtuullisen kevyttä liikuntaa jota jaksaa toistaa, sekä kokonaismäärää yksittäisten kovien harjoitusten sijaan.",
  ),
  RECOVERY(
    "Palautuminen ja kevyempi harjoittelu",
    "Kevennä kokonaiskuormaa: vähemmän kovia harjoituksia, enemmän palauttavaa liikettä ja lepopäiviä.",
  ),
  OWN("Oma tavoite", ""),
}

/**
 * How the whole programme felt, asked once at the end.
 *
 * Separate from a session's RPE and from "miltä tuntui", because it answers a different question:
 * those are about one workout on one day, and this is about whether the *block* was pitched right.
 * It is the one piece of evidence the measurements cannot supply — a programme can look successful
 * in every figure and still have been more than the person wanted to carry.
 */
enum class ProgramFit(val title: String, val hint: String) {
  TOO_EASY(
    "Liian kevyt",
    "Käyttäjä koki edellisen jakson liian kevyeksi. Voit nostaa kuormaa selvemmin kuin data yksin " +
      "antaisi aihetta.",
  ),
  SLIGHTLY_EASY("Hieman liian kevyt", "Käyttäjä koki edellisen jakson hieman liian kevyeksi."),
  RIGHT("Sopiva", "Käyttäjä koki edellisen jakson sopivaksi."),
  SLIGHTLY_HARD(
    "Hieman liian raskas",
    "Käyttäjä koki edellisen jakson hieman liian raskaaksi. Älä nosta kokonaiskuormaa.",
  ),
  TOO_HARD(
    "Liian raskas",
    "Käyttäjä koki edellisen jakson liian raskaaksi. Kevennä kokonaiskuormaa, vaikka mittarit " +
      "näyttäisivät kehitystä.",
  ),
}

/** Everything the person chose before the next programme is generated. */
data class NextProgramRequest(
  val goal: NextProgramGoal,
  /** Free text, used when [goal] is [NextProgramGoal.OWN] and as an extra note otherwise. */
  val ownGoal: String? = null,
  /** `null` when the question was skipped — absent, not "sopiva". */
  val fit: ProgramFit? = null,
  val weeks: Int = DEFAULT_NEXT_PROGRAM_WEEKS,
)

/** Eight, because that is the block length the owner asked for and the previous plans used. */
const val DEFAULT_NEXT_PROGRAM_WEEKS = 8

/** Exact structural contract for generated programmes, separate from free-text coaching goals. */
data class NextProgramRequirements(val startDate: LocalDate, val timeZone: String, val weeks: Int)

/**
 * The plan the model produced, described by the app rather than by the model.
 *
 * **The summary shown before saving is computed from the plan itself.** Asking the model to
 * describe what it just wrote invites a description that flatters the plan or quietly disagrees
 * with it; counting the sessions cannot. The one thing left to the model is the plan's own
 * `description`, which is its stated intent and belongs to it.
 */
data class NextProgramSummary(
  val name: String,
  /** The plan's own description — the model's statement of what the block is for. */
  val statedGoal: String?,
  val startDate: LocalDate,
  val endDate: LocalDate,
  val weeks: Int,
  val sessions: Int,
  /** Sessions per week, rounded to one decimal. */
  val sessionsPerWeek: Double,
  val byType: Map<WorkoutType, Int>,
  val runShape: NextProgramRunShape?,
  val strengthShape: NextProgramStrengthShape?,
  val progression: NextProgramProgression,
  /** How this differs from the programme that just ended. Empty when there is nothing to compare. */
  val changesFromPrevious: List<String>,
)

data class NextProgramRunShape(
  val sessions: Int,
  val weeklyKmFirstWeek: Double?,
  val weeklyKmLastWeek: Double?,
  val longestRunKm: Double?,
  val byIntensity: Map<Intensity, Int>,
)

data class NextProgramStrengthShape(
  val sessions: Int,
  val perWeek: Double,
  val recurringMovements: List<String>,
)

/** Which way the plan's weekly running volume moves, and by how much. */
enum class NextProgramProgression(val title: String) {
  RISING("nouseva"),
  FLAT("tasainen"),
  FALLING("laskeva"),
  UNKNOWN("ei pääteltävissä"),
}

/**
 * Describes a generated plan from its own sessions.
 *
 * Takes plain [TrainingSession]s rather than the importer's `ValidatedPlan`, so this stays in the
 * domain and can be tested without the data layer — the validated plan's sessions are already
 * these.
 */
fun summariseProgramPlan(
  name: String,
  description: String?,
  sessions: List<TrainingSession>,
  previous: TrainingProgramAnalysis? = null,
): NextProgramSummary? {
  if (sessions.isEmpty()) return null
  val dated =
    sessions
      .mapNotNull { session -> runCatching { LocalDate.parse(session.scheduledDate) }.getOrNull()?.let { it to session } }
      .sortedBy { it.first }
  if (dated.isEmpty()) return null

  val weeks = sessions.map { it.weekNumber }.distinct().size.coerceAtLeast(1)
  val runs = sessions.filter { it.type == WorkoutType.RUNNING }
  val strength = sessions.filter { it.type == WorkoutType.STRENGTH }

  val kmByWeek =
    runs
      .filter { it.distanceKm != null }
      .groupBy { it.weekNumber }
      .mapValues { (_, inWeek) -> inWeek.sumOf { it.distanceKm ?: 0.0 } }
      .toSortedMap()

  return NextProgramSummary(
    name = name,
    statedGoal = description?.trim()?.takeIf { it.isNotEmpty() },
    startDate = dated.first().first,
    endDate = dated.last().first,
    weeks = weeks,
    sessions = sessions.size,
    sessionsPerWeek = (sessions.size.toDouble() / weeks).round(1),
    byType = sessions.groupingBy { it.type }.eachCount(),
    runShape =
      runs
        .takeIf { it.isNotEmpty() }
        ?.let {
          NextProgramRunShape(
            sessions = runs.size,
            weeklyKmFirstWeek = kmByWeek.entries.firstOrNull()?.value?.round(1),
            weeklyKmLastWeek = kmByWeek.entries.lastOrNull()?.value?.round(1),
            longestRunKm = runs.mapNotNull { session -> session.distanceKm }.maxOrNull()?.round(1),
            byIntensity =
              runs.mapNotNull { session -> session.intensity }.groupingBy { intensity -> intensity }.eachCount(),
          )
        },
    strengthShape =
      strength
        .takeIf { it.isNotEmpty() }
        ?.let {
          NextProgramStrengthShape(
            sessions = strength.size,
            perWeek = (strength.size.toDouble() / weeks).round(1),
            recurringMovements =
              strength
                .flatMap { session -> session.exercises.orEmpty().map { it.name } }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(6)
                .map { it.key },
          )
        },
    progression = progressionOf(kmByWeek),
    changesFromPrevious = changesFrom(previous, sessions.size, weeks, kmByWeek),
  )
}

/**
 * Which way the weekly kilometres move.
 *
 * Read from the first week against the last rather than by fitting a line, because a plan is eight
 * points and the question is only "up, down or level". [FLAT_TOLERANCE] keeps a plan that varies by
 * a kilometre from being called a progression — a taper week is not a downward trend.
 */
private fun progressionOf(kmByWeek: Map<Int, Double>): NextProgramProgression {
  if (kmByWeek.size < 2) return NextProgramProgression.UNKNOWN
  val first = kmByWeek.entries.first().value
  val last = kmByWeek.entries.last().value
  val change = last - first
  return when {
    abs(change) <= FLAT_TOLERANCE -> NextProgramProgression.FLAT
    change > 0 -> NextProgramProgression.RISING
    else -> NextProgramProgression.FALLING
  }
}

private const val FLAT_TOLERANCE = 1.0

/** A quarter of a session a week is scheduling noise, not a change in how much training there is. */
private const val SAME_PER_WEEK = 0.25

private fun changesFrom(
  previous: TrainingProgramAnalysis?,
  sessions: Int,
  weeks: Int,
  kmByWeek: Map<Int, Double>,
): List<String> {
  if (previous == null) return emptyList()
  return buildList {
    // Both lines compare what was **done** with what is **planned**, and both say so. Comparing the
    // old plan's intentions with the new plan's would hide the case that matters most: a programme
    // that was only half carried out, followed by one that asks for more than the last one did.
    val previousPerWeek =
      previous.adherence.completed.toDouble() / previous.identity.weekCount.coerceAtLeast(1)
    val nextPerWeek = sessions.toDouble() / weeks
    if (abs(nextPerWeek - previousPerWeek) < SAME_PER_WEEK) {
      add("Harjoituksia viikossa suunnilleen yhtä monta kuin edellisessä toteutui")
    } else {
      add(
        "Harjoituksia viikossa ${previousPerWeek.round(1)} (toteutunut) → " +
          "${nextPerWeek.round(1)} (suunniteltu)"
      )
    }

    val previousWeeklyKm =
      previous.weeks.mapNotNull { it.runKm }.takeIf { it.isNotEmpty() }?.average()
    val nextWeeklyKm = kmByWeek.values.takeIf { it.isNotEmpty() }?.average()
    if (previousWeeklyKm != null && nextWeeklyKm != null) {
      add(
        "Juoksua viikossa ${previousWeeklyKm.round(1)} km (toteutunut) → " +
          "${nextWeeklyKm.round(1)} km (suunniteltu)"
      )
    }
  }
}

private fun Double.round(decimals: Int): Double {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  return (this * factor).roundToInt() / factor
}
