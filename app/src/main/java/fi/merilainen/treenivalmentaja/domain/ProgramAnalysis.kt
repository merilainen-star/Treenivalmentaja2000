package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Everything a whole-programme report is allowed to reason about, computed by the app.
 *
 * **The app does the arithmetic; the model does the reading.** A model handed a hundred sessions of
 * raw rows will count them badly, silently, and differently each time it is asked. So every count,
 * percentage, weekly rollup and first-against-last comparison is settled here, in a pure function
 * with tests, and what reaches the model is a structure in which only the meaning is still open.
 *
 * See `docs/PROGRAM_ANALYSIS.md` for why each part is at the resolution it is — in short, a run
 * keeps its own row because two runs eight weeks apart are the entire evidence for "did the fitness
 * move", and a strength session does not because its per-set detail answers no question this report
 * asks.
 */
data class TrainingProgramAnalysis(
  val identity: ProgramIdentity,
  val asOf: LocalDate,
  val phase: ProgramPhase,
  val adherence: ProgramAdherence,
  val weeks: List<ProgramWeek>,
  /** Every completed run, oldest first. */
  val runs: List<ProgramRun>,
  /** One per planned intensity that had enough runs to compare. See [ProgramRunTrend]. */
  val runTrends: List<ProgramRunTrend>,
  /**
   * Where the effort actually went, by planned intensity. See [ProgramZoneSplit].
   *
   * Empty when no run in the programme carried a zone distribution — runs synced before the app
   * asked for one, or a watch with no heart-rate strap.
   */
  val zoneSplits: List<ProgramZoneSplit>,
  /**
   * Run groups that could not be compared, and how many runs they had.
   *
   * Reported rather than dropped: "there were three easy runs, which is too few to call a trend" is
   * an answer, and a model that cannot see the omission will explain the silence by inventing one.
   */
  val runGroupsTooSmall: List<Pair<Intensity?, Int>>,
  val strength: ProgramStrength?,
  val feedback: ProgramFeedback,
  val recovery: ProgramRecoveryTrend?,
  val changes: List<ProgramChange>,
  /** `null` once the programme is over — there is nothing left to say about what remains. */
  val remaining: ProgramRemaining?,
)

/** The plan as its author wrote it. [goals] is the plan's own description, quoted, never summarised. */
data class ProgramIdentity(
  val name: String,
  val goals: String?,
  val startDate: LocalDate,
  val endDate: LocalDate,
  val weekCount: Int,
)

sealed interface ProgramPhase {
  /** [week] is the week the report is being asked from, 1-based. */
  data class InProgress(val week: Int, val ofWeeks: Int) : ProgramPhase

  data object Finished : ProgramPhase
}

/**
 * How much of the plan was actually carried out.
 *
 * [completionRate] counts a completed session against those that were decided and not done —
 * skipped and interrupted. **Rescheduled and cancelled sessions are excluded**, for the reasons the
 * status table already gives: a rescheduled session did not go missing, it has a successor row that
 * is counted in its place, and a cancelled one was removed from the plan rather than missed.
 */
data class ProgramAdherence(
  val planned: Int,
  val completed: Int,
  val skipped: Int,
  val interrupted: Int,
  val rescheduled: Int,
  val cancelled: Int,
  val stillOpen: Int,
  val byType: Map<WorkoutType, TypeAdherence>,
) {
  val decided: Int
    get() = completed + skipped + interrupted

  /** `null` when nothing has been decided yet — a rate out of zero is not zero per cent. */
  val completionRate: Int?
    get() = if (decided == 0) null else (completed * 100.0 / decided).roundToInt()
}

data class TypeAdherence(val completed: Int, val skipped: Int, val interrupted: Int, val open: Int)

/** One week of the plan, as it was planned and as it went. */
data class ProgramWeek(
  val week: Int,
  val planned: Int,
  val completed: Int,
  val skipped: Int,
  val interrupted: Int,
  val open: Int,
  val runKm: Double?,
  val minutes: Int?,
  val trainingLoad: Int?,
  val meanRpe: Double?,
)

/**
 * One completed run, at the fidelity that makes two of them comparable months apart.
 *
 * Everything here is measured except [plannedIntensity], which is what the plan asked for and is
 * the key the trends are grouped by: an easy run and an interval session share a unit and nothing
 * else, and comparing them is the commonest way to manufacture progress that did not happen.
 */
data class ProgramRun(
  val date: LocalDate,
  val week: Int,
  val plannedIntensity: Intensity?,
  val distanceKm: Double?,
  val durationSec: Long?,
  val paceSecPerKm: Int?,
  val avgHeartRate: Int?,
  val maxHeartRate: Int?,
  val elevationGainMeters: Int?,
  val trainingLoad: Int?,
  val trimp: Double?,
  val rpe: Int?,
  val feel: String?,
)

/**
 * Total time in each heart-rate zone across the runs of one planned intensity.
 *
 * **The measurement that says whether the programme was run as written.** A block of easy runs that
 * averaged 148 bpm might be eight easy runs, or six easy runs and two that were raced; the averages
 * cannot tell those apart and this can. It is also the honest way to answer the commonest question
 * about a training block — "was I doing the easy days easy" — which no summary figure has ever been
 * able to answer.
 *
 * Aggregated **by zone number**, and the beat ranges are kept only where every contributing run
 * agreed on them. Zones move when the athlete's threshold is recomputed, and a range that was true
 * in week 1 and false in week 7 would be a fabricated fact about half the runs. See
 * [ProgramZoneTotal.label].
 */
data class ProgramZoneSplit(
  val intensity: Intensity?,
  /** How many runs contributed. Fewer than the group's total when some carried no zone data. */
  val runs: Int,
  val zones: List<ProgramZoneTotal>,
) {

  val totalSeconds: Long
    get() = zones.sumOf { it.seconds }

  /** That zone's share of the group, 0–100. `null` when the group recorded no time at all. */
  fun percentOf(zone: ProgramZoneTotal): Int? {
    val total = totalSeconds
    if (total <= 0L) return null
    return (zone.seconds * 100.0 / total).roundToInt()
  }
}

/** One zone's total across a group of runs. */
data class ProgramZoneTotal(
  val zone: Int,
  /**
   * The beat range, when every run in the group had the same one — `124–145`, or `–123` for the
   * bottom zone. `null` when the zone table moved during the programme, which is a real event and
   * not a reason to quote one week's numbers over another's.
   */
  val bpmRange: String?,
  val seconds: Long,
) {
  /** `Z2 (124–145)`, or `Z2` when the range could not be stated. */
  val label: String
    get() = bpmRange?.let { "Z$zone ($it)" } ?: "Z$zone"
}

/**
 * The first half of a group of runs against the second half.
 *
 * This exists so the model never has to find the trend itself. It is computed only where it can be
 * computed honestly — see [MINIMUM_RUNS_PER_HALF] — and the three readings the comparison is for
 * fall straight out of the pairs: the same pace at a lower heart rate, a faster pace at the same
 * heart rate, and a longer distance at the same effort.
 */
data class ProgramRunTrend(
  val intensity: Intensity?,
  val earlyCount: Int,
  val lateCount: Int,
  val earlyPaceSecPerKm: Int?,
  val latePaceSecPerKm: Int?,
  val earlyAvgHeartRate: Int?,
  val lateAvgHeartRate: Int?,
  val earlyMeanKm: Double?,
  val lateMeanKm: Double?,
) {
  /** Negative is faster. `null` unless both halves had a pace. */
  val paceDeltaSec: Int?
    get() = pair(earlyPaceSecPerKm, latePaceSecPerKm) { a, b -> b - a }

  /** Negative is a lower heart rate for the same work. */
  val heartRateDelta: Int?
    get() = pair(earlyAvgHeartRate, lateAvgHeartRate) { a, b -> b - a }

  val distanceDeltaKm: Double?
    get() = if (earlyMeanKm == null || lateMeanKm == null) null else lateMeanKm - earlyMeanKm

  private fun <T> pair(a: T?, b: T?, f: (T, T) -> Int): Int? = if (a == null || b == null) null else f(a, b)
}

/**
 * The strength work, summarised rather than enumerated.
 *
 * [recurringMovements] is what the plan kept coming back to, which is the useful thing to name in a
 * report; a full list of every movement in every session is the plan read back aloud.
 */
data class ProgramStrength(
  val completed: Int,
  val skipped: Int,
  val interrupted: Int,
  val fullyTickedOff: Int,
  val meanDurationMin: Int?,
  val recurringMovements: List<String>,
  val earlyMeanRpe: Double?,
  val lateMeanRpe: Double?,
)

/**
 * What the person said about the training, aggregated the same way the measurements are.
 *
 * The point of keeping the early and late means apart is the case the owner named: pace and heart
 * rate improving while every session is still reported as heavy is a finding, and a report that
 * reads only the numbers will miss it.
 */
data class ProgramFeedback(
  val ratedSessions: Int,
  val meanRpe: Double?,
  val earlyMeanRpe: Double?,
  val lateMeanRpe: Double?,
  /** How often each "miltä tuntui" answer was given, most common first. */
  val feelCounts: List<Pair<String, Int>>,
  val earlyFeelCounts: List<Pair<String, Int>>,
  val lateFeelCounts: List<Pair<String, Int>>,
)

/** The mornings across the programme, first half against second. Same rule: too few days, no trend. */
data class ProgramRecoveryTrend(
  val days: Int,
  val earlyMeanHrvMs: Int?,
  val lateMeanHrvMs: Int?,
  val earlyMeanRestingHr: Int?,
  val lateMeanRestingHr: Int?,
  val earlyMeanReadiness: Int?,
  val lateMeanReadiness: Int?,
)

/** Something that happened to the plan rather than in a session. */
data class ProgramChange(
  val date: LocalDate,
  val week: Int,
  val type: WorkoutType,
  val kind: ProgramChangeKind,
)

enum class ProgramChangeKind(val title: String) {
  RESCHEDULED("siirretty toiselle päivälle"),
  LIGHTER("kevyempi versio"),
  CANCELLED("poistettu ohjelmasta"),
  ILLNESS_PAUSE("tauolla sairauden vuoksi"),
}

/** What the plan still asks for. `null` on a finished programme. */
data class ProgramRemaining(
  val sessions: Int,
  val weeks: Int,
  val byType: Map<WorkoutType, Int>,
  val plannedRunKm: Double?,
  val lastDate: LocalDate,
)

/**
 * How many runs each half of a group needs before the app will call the difference a trend.
 *
 * Two, and not one: a single run against a single run is a comparison of two days' weather, sleep
 * and terrain, and dressing it as progress is exactly the invention the report is meant to prevent.
 */
const val MINIMUM_RUNS_PER_HALF = 2

/** One session of the plan with everything the programme report reads about it. */
data class ProgramSessionRecord(
  val session: TrainingSession,
  /** The guided workout's completion payload: RPE, "miltä tuntui", what was ticked off. */
  val outcome: ActiveWorkoutOutcome? = null,
  /** The watch's measurements, where an activity was matched to this session. */
  val run: CompletedRunMetrics? = null,
  /** Oura's own record of the session, used for a duration when the watch has none. */
  val oura: CompletedSessionMetrics? = null,
)

/**
 * Turns a plan and its sessions into the one object the report prompt renders.
 *
 * Pure, and takes [today] rather than reading a clock, for the same reason every rule in this
 * project does: a report is a claim about a moment, and a test that cannot fix the moment cannot
 * check the claim.
 */
fun buildProgramAnalysis(
  planName: String,
  planDescription: String?,
  sessions: List<ProgramSessionRecord>,
  recoveryByDay: Map<LocalDate, DailyRecovery> = emptyMap(),
  today: LocalDate,
): TrainingProgramAnalysis? {
  if (sessions.isEmpty()) return null

  val dated = sessions.mapNotNull { record -> record.date()?.let { it to record } }.sortedBy { it.first }
  if (dated.isEmpty()) return null
  val records = dated.map { it.second }

  val identity =
    ProgramIdentity(
      name = planName,
      goals = planDescription?.trim()?.takeIf { it.isNotEmpty() },
      startDate = dated.first().first,
      endDate = dated.last().first,
      weekCount = records.maxOf { it.session.weekNumber },
    )

  val open = records.filter { it.session.status.isOpen }
  val phase =
    if (open.isEmpty()) ProgramPhase.Finished
    else
      ProgramPhase.InProgress(
        week = currentWeek(dated, today, identity.weekCount),
        ofWeeks = identity.weekCount,
      )

  val completedRuns =
    records
      .filter { it.session.type == WorkoutType.RUNNING && it.session.status == SessionStatus.COMPLETED }
      .mapNotNull { it.toProgramRun() }

  val grouped = completedRuns.groupBy { it.plannedIntensity }
  val trends = grouped.mapNotNull { (intensity, runs) -> runTrend(intensity, runs) }
  val tooSmall =
    grouped
      .filter { (intensity, runs) -> trends.none { it.intensity == intensity } && runs.isNotEmpty() }
      .map { (intensity, runs) -> intensity to runs.size }
      .sortedByDescending { it.second }

  return TrainingProgramAnalysis(
    identity = identity,
    asOf = today,
    phase = phase,
    adherence = adherenceOf(records),
    weeks = weeksOf(records),
    runs = completedRuns,
    runTrends = trends.sortedBy { it.intensity?.ordinal ?: Int.MAX_VALUE },
    zoneSplits = zoneSplitsOf(records),
    runGroupsTooSmall = tooSmall,
    strength = strengthOf(records),
    feedback = feedbackOf(records),
    recovery = recoveryTrend(recoveryByDay, identity.startDate, minOf(identity.endDate, today)),
    changes = changesOf(dated),
    remaining = remainingOf(dated, today).takeIf { phase !is ProgramPhase.Finished },
  )
}

// ------------------------------------------------------------------------------- the parts

private fun ProgramSessionRecord.date(): LocalDate? = runCatching { LocalDate.parse(session.scheduledDate) }.getOrNull()

/**
 * The week the report is being asked from.
 *
 * Read off the sessions rather than by dividing days by seven, because a plan's own week numbering
 * is what its author wrote and the two disagree the moment a plan starts mid-week.
 */
private fun currentWeek(dated: List<Pair<LocalDate, ProgramSessionRecord>>, today: LocalDate, weeks: Int): Int {
  val atOrBefore = dated.lastOrNull { it.first <= today }?.second?.session?.weekNumber
  val next = dated.firstOrNull { it.first > today }?.second?.session?.weekNumber
  return (atOrBefore ?: next ?: 1).coerceIn(1, weeks.coerceAtLeast(1))
}

private fun adherenceOf(records: List<ProgramSessionRecord>): ProgramAdherence {
  fun count(status: SessionStatus) = records.count { it.session.status == status }
  return ProgramAdherence(
    planned = records.size,
    completed = count(SessionStatus.COMPLETED),
    skipped = count(SessionStatus.SKIPPED),
    interrupted = count(SessionStatus.INTERRUPTED),
    rescheduled = count(SessionStatus.RESCHEDULED),
    cancelled = count(SessionStatus.CANCELLED),
    stillOpen = records.count { it.session.status.isOpen },
    byType =
      records
        .groupBy { it.session.type }
        .mapValues { (_, forType) ->
          TypeAdherence(
            completed = forType.count { it.session.status == SessionStatus.COMPLETED },
            skipped = forType.count { it.session.status == SessionStatus.SKIPPED },
            interrupted = forType.count { it.session.status == SessionStatus.INTERRUPTED },
            open = forType.count { it.session.status.isOpen },
          )
        },
  )
}

private fun weeksOf(records: List<ProgramSessionRecord>): List<ProgramWeek> =
  records
    .groupBy { it.session.weekNumber }
    .toSortedMap()
    .map { (week, inWeek) ->
      val done = inWeek.filter { it.session.status == SessionStatus.COMPLETED }
      ProgramWeek(
        week = week,
        planned = inWeek.size,
        completed = done.size,
        skipped = inWeek.count { it.session.status == SessionStatus.SKIPPED },
        interrupted = inWeek.count { it.session.status == SessionStatus.INTERRUPTED },
        open = inWeek.count { it.session.status.isOpen },
        runKm = done.mapNotNull { it.run?.distanceKm }.takeIf { it.isNotEmpty() }?.sum()?.round(1),
        minutes = done.mapNotNull { it.durationMinutes() }.takeIf { it.isNotEmpty() }?.sum(),
        trainingLoad = done.mapNotNull { it.run?.trainingLoad }.takeIf { it.isNotEmpty() }?.sum(),
        meanRpe = done.mapNotNull { it.outcome?.sessionRpe }.meanOrNull()?.round(1),
      )
    }

/** The watch first, then Oura, then the plan — measurement before intention, in that order. */
private fun ProgramSessionRecord.durationMinutes(): Int? =
  run?.primaryDurationSec?.let { (it / 60).toInt() }
    ?: oura?.durationMin
    ?: session.durationMin?.takeIf { it > 0 }

/**
 * Adds up the zone times of every completed run that carried them, grouped by planned intensity.
 *
 * Runs without a zone distribution are counted out entirely rather than treated as zeros: a run the
 * strap did not record is not a run spent in Z1, and folding it in as one would make every group
 * look easier than it was. A group where no run carried zones produces no entry at all.
 *
 * The beat range for a zone survives only if every contributing run agreed on it — see
 * [ProgramZoneTotal.bpmRange].
 */
private fun zoneSplitsOf(records: List<ProgramSessionRecord>): List<ProgramZoneSplit> =
  records
    .filter { it.session.type == WorkoutType.RUNNING && it.session.status == SessionStatus.COMPLETED }
    .mapNotNull { record -> record.run?.heartRateZones?.let { record.session.intensity to it } }
    .groupBy({ it.first }, { it.second })
    .mapNotNull { (intensity, distributions) ->
      val zoneCount = distributions.maxOf { it.zones.size }
      if (zoneCount == 0) return@mapNotNull null
      val totals =
        (1..zoneCount).map { number ->
          val entries = distributions.mapNotNull { it.zones.getOrNull(number - 1) }
          val ranges = entries.map { bpmRange(it) }.distinct()
          ProgramZoneTotal(
            zone = number,
            bpmRange = ranges.singleOrNull(),
            seconds = entries.sumOf { it.seconds },
          )
        }
      ProgramZoneSplit(intensity = intensity, runs = distributions.size, zones = totals)
    }
    .sortedBy { it.intensity?.ordinal ?: Int.MAX_VALUE }

private fun bpmRange(zone: HeartRateZoneTime): String? {
  val high = zone.highBpm ?: return null
  val low = zone.lowBpm
  return if (low == null) "–$high" else "$low–$high"
}

private fun ProgramSessionRecord.toProgramRun(): ProgramRun? {
  val date = date() ?: return null
  return ProgramRun(
    date = date,
    week = session.weekNumber,
    plannedIntensity = session.intensity,
    distanceKm = run?.distanceKm?.round(2) ?: session.distanceKm,
    durationSec = run?.primaryDurationSec ?: oura?.durationMin?.let { it * 60L },
    paceSecPerKm = run?.paceSecPerKm,
    avgHeartRate = run?.avgHeartRate ?: oura?.avgHeartRate,
    maxHeartRate = run?.maxHeartRate ?: oura?.maxHeartRate,
    elevationGainMeters = run?.elevationGainMeters,
    trainingLoad = run?.trainingLoad,
    trimp = run?.trimp?.round(1),
    rpe = outcome?.sessionRpe,
    feel = outcome?.feel,
  )
}

/**
 * Splits a group of runs down the middle by date and compares the halves.
 *
 * `null` unless both halves clear [MINIMUM_RUNS_PER_HALF]. The caller reports the group as
 * uncomparable rather than dropping it, because a missing trend that is explained is information
 * and a missing trend that is silent is a hole the model will fill.
 */
private fun runTrend(intensity: Intensity?, runs: List<ProgramRun>): ProgramRunTrend? {
  if (runs.size < MINIMUM_RUNS_PER_HALF * 2) return null
  val ordered = runs.sortedBy { it.date }
  val early = ordered.take(ordered.size / 2)
  val late = ordered.drop((ordered.size + 1) / 2)
  if (early.size < MINIMUM_RUNS_PER_HALF || late.size < MINIMUM_RUNS_PER_HALF) return null
  return ProgramRunTrend(
    intensity = intensity,
    earlyCount = early.size,
    lateCount = late.size,
    earlyPaceSecPerKm = early.mapNotNull { it.paceSecPerKm }.meanOrNull()?.roundToInt(),
    latePaceSecPerKm = late.mapNotNull { it.paceSecPerKm }.meanOrNull()?.roundToInt(),
    earlyAvgHeartRate = early.mapNotNull { it.avgHeartRate }.meanOrNull()?.roundToInt(),
    lateAvgHeartRate = late.mapNotNull { it.avgHeartRate }.meanOrNull()?.roundToInt(),
    earlyMeanKm = early.mapNotNull { it.distanceKm }.meanOrNull()?.round(2),
    lateMeanKm = late.mapNotNull { it.distanceKm }.meanOrNull()?.round(2),
  )
}

private fun strengthOf(records: List<ProgramSessionRecord>): ProgramStrength? {
  val strength = records.filter { it.session.type == WorkoutType.STRENGTH }
  if (strength.isEmpty()) return null
  val done = strength.filter { it.session.status == SessionStatus.COMPLETED }
  val (early, late) = done.halves()
  return ProgramStrength(
    completed = done.size,
    skipped = strength.count { it.session.status == SessionStatus.SKIPPED },
    interrupted = strength.count { it.session.status == SessionStatus.INTERRUPTED },
    fullyTickedOff = done.count { it.outcome?.guided?.isComplete == true },
    meanDurationMin = done.mapNotNull { it.durationMinutes() }.meanOrNull()?.roundToInt(),
    // Named only where the plan kept coming back to them. A movement done once is a detail of one
    // session, and this report is not about one session.
    recurringMovements =
      strength
        .flatMap { it.session.exercises.orEmpty().map { exercise -> exercise.name } }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(8)
        .map { it.key },
    earlyMeanRpe = early.mapNotNull { it.outcome?.sessionRpe }.meanOrNull()?.round(1),
    lateMeanRpe = late.mapNotNull { it.outcome?.sessionRpe }.meanOrNull()?.round(1),
  )
}

private fun feedbackOf(records: List<ProgramSessionRecord>): ProgramFeedback {
  val rated = records.filter { it.session.status == SessionStatus.COMPLETED && it.outcome != null }
  val (early, late) = rated.halves()
  fun List<ProgramSessionRecord>.feels(): List<Pair<String, Int>> =
    mapNotNull { it.outcome?.feel?.trim()?.takeIf { feel -> feel.isNotEmpty() } }
      .groupingBy { it }
      .eachCount()
      .entries
      .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
      .map { it.key to it.value }
  return ProgramFeedback(
    ratedSessions = rated.count { it.outcome?.sessionRpe != null },
    meanRpe = rated.mapNotNull { it.outcome?.sessionRpe }.meanOrNull()?.round(1),
    earlyMeanRpe = early.mapNotNull { it.outcome?.sessionRpe }.meanOrNull()?.round(1),
    lateMeanRpe = late.mapNotNull { it.outcome?.sessionRpe }.meanOrNull()?.round(1),
    feelCounts = rated.feels(),
    earlyFeelCounts = early.feels(),
    lateFeelCounts = late.feels(),
  )
}

private fun recoveryTrend(
  byDay: Map<LocalDate, DailyRecovery>,
  from: LocalDate,
  to: LocalDate,
): ProgramRecoveryTrend? {
  val inRange =
    byDay
      .filterKeys { !it.isBefore(from) && !it.isAfter(to) }
      .toSortedMap()
      .values
      .filter { it.averageHrvMs != null || it.restingHeartRate != null || it.readiness != null }
  // Four days is the same rule the run trends keep: two a side, or no comparison at all.
  if (inRange.size < MINIMUM_RUNS_PER_HALF * 2) return null
  val ordered = inRange.toList()
  val early = ordered.take(ordered.size / 2)
  val late = ordered.drop((ordered.size + 1) / 2)
  return ProgramRecoveryTrend(
    days = ordered.size,
    earlyMeanHrvMs = early.mapNotNull { it.averageHrvMs }.meanOrNull()?.roundToInt(),
    lateMeanHrvMs = late.mapNotNull { it.averageHrvMs }.meanOrNull()?.roundToInt(),
    earlyMeanRestingHr = early.mapNotNull { it.restingHeartRate }.meanOrNull()?.roundToInt(),
    lateMeanRestingHr = late.mapNotNull { it.restingHeartRate }.meanOrNull()?.roundToInt(),
    earlyMeanReadiness = early.mapNotNull { it.readiness }.meanOrNull()?.roundToInt(),
    lateMeanReadiness = late.mapNotNull { it.readiness }.meanOrNull()?.roundToInt(),
  )
}

private fun changesOf(dated: List<Pair<LocalDate, ProgramSessionRecord>>): List<ProgramChange> =
  dated.mapNotNull { (date, record) ->
    val kind =
      when {
        record.session.status == SessionStatus.RESCHEDULED -> ProgramChangeKind.RESCHEDULED
        record.session.status == SessionStatus.CANCELLED -> ProgramChangeKind.CANCELLED
        record.session.status == SessionStatus.PAUSED_DUE_TO_ILLNESS -> ProgramChangeKind.ILLNESS_PAUSE
        // Read off the session rather than off its status, because a lightened session goes on to
        // be completed and its status then says COMPLETED — the flag is what remembers.
        record.session.appliedLighterVariant -> ProgramChangeKind.LIGHTER
        else -> null
      }
    kind?.let { ProgramChange(date = date, week = record.session.weekNumber, type = record.session.type, kind = it) }
  }

private fun remainingOf(dated: List<Pair<LocalDate, ProgramSessionRecord>>, today: LocalDate): ProgramRemaining? {
  val open = dated.filter { it.second.session.status.isOpen }
  if (open.isEmpty()) return null
  return ProgramRemaining(
    sessions = open.size,
    weeks = open.map { it.second.session.weekNumber }.distinct().size,
    byType = open.groupingBy { it.second.session.type }.eachCount(),
    plannedRunKm =
      open
        .filter { it.second.session.type == WorkoutType.RUNNING }
        .mapNotNull { it.second.session.distanceKm }
        .takeIf { it.isNotEmpty() }
        ?.sum()
        ?.round(1),
    lastDate = maxOf(open.last().first, today),
  )
}

// ------------------------------------------------------------------------------- small arithmetic

/**
 * Splits chronologically ordered records down the middle.
 *
 * An odd count gives the middle item to neither half: it belongs to both stories equally, and
 * putting it in one of them is a thumb on the scale of whichever comparison it lands in.
 */
private fun List<ProgramSessionRecord>.halves(): Pair<List<ProgramSessionRecord>, List<ProgramSessionRecord>> {
  val ordered = sortedBy { it.session.scheduledDate }
  return ordered.take(ordered.size / 2) to ordered.drop((ordered.size + 1) / 2)
}

private fun Collection<Number>.meanOrNull(): Double? =
  if (isEmpty()) null else sumOf { it.toDouble() } / size

private fun Double.round(decimals: Int): Double {
  var factor = 1.0
  repeat(decimals) { factor *= 10 }
  return (this * factor).roundToInt() / factor
}
