package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Whether the athlete's easy sessions have stopped being easy.
 *
 * Like [ReadinessAdvice] this is an observation and never an action — but where that one offers two
 * buttons, this one offers none, and the difference is deliberate. The sessions it talks about are
 * already run, and lightening a session that is *supposed* to be light is incoherent. The useful
 * thing is the reminder arriving on the morning of the next easy one rather than a plan edit after
 * the fact.
 */
sealed interface EasyRunDrift {

  /** Nothing to say — including every case where there is not enough history to say it. */
  data object None : EasyRunDrift

  /**
   * Three easy sessions in a row run harder than this athlete's own easy sessions usually are.
   *
   * Not one hard easy run: that is a Tuesday, a hill, a headwind. Three in a row is a finding,
   * because base training that is not easy stops being base training and the next hard session
   * arrives on tired legs.
   *
   * @param sessionId today's easy session the card belongs to. The finding is *about* the three
   *   that came before, and it is shown next to this one because that is the session it can still
   *   change.
   * @param type what kind of session those three were — the same type as today's, since a run and
   *   a ski session are not each other's baseline.
   * @param recentIntensityPercent the three, most recent first, as whole percentages of threshold.
   * @param medianIntensityPercent the middle of every comparable stored session, which is what
   *   "usually" means here. Rounded for display only; the comparison itself is made on the
   *   unrounded value.
   * @param comparableSessions how many sessions the median was taken over, so the card can say
   *   what the claim rests on.
   */
  data class Finding(
    val sessionId: String,
    val type: WorkoutType,
    val recentIntensityPercent: List<Int>,
    val medianIntensityPercent: Int,
    val comparableSessions: Int,
  ) : EasyRunDrift
}

/**
 * Decides whether this morning's easy session is worth a word about the three before it.
 *
 * A pure function of its inputs — no repository, no clock, no I/O, and no AI — so every branch is
 * a unit test rather than something only a real month of training could produce. It writes
 * nothing: unusually for a rule in this app, it needs no engine operation at all, because it
 * proposes no change.
 *
 * **The measure is `icu_intensity`, not `icu_training_load`.** Load grows with duration, so a long
 * calm run scores high without being hard — it answers "what did this cost". Intensity is a
 * percentage of threshold and therefore comparable across runs of different lengths; it answers
 * "was this hard", which is the question being asked.
 *
 * **The baseline is the athlete's own history, not a fixed band.** Writing "easy means under 75 %
 * of threshold" would put invented physiology in the code, which this project has avoided
 * everywhere else — [DailyRecovery.readinessLabel] shares Oura's own bands rather than inventing a
 * second opinion. The comparison is instead against the median of this athlete's own comparable
 * sessions: self-calibrating, needing no invented number, and stating a claim the person can check
 * for themselves.
 *
 * **The median includes the three being judged.** They are comparable sessions like any other, and
 * excluding them would quietly turn "harder than usual" into "harder than it used to be" — a
 * different claim, and one that gets easier to make the longer the drift goes on. Keeping them in
 * is the conservative direction: three genuinely drifting runs pull the median towards themselves
 * and make the rule harder to satisfy, never easier.
 */
class EasyRunDriftUseCase {

  /**
   * @param sessions the plan's sessions, completed ones included.
   * @param runMetricsBySession what the watch recorded for each session that was matched to an
   *   activity, keyed by session id — `IntervalsRepository.observeMatchedRunMetrics`.
   */
  fun execute(
    today: LocalDate,
    sessions: List<TrainingSession>,
    runMetricsBySession: Map<String, CompletedRunMetrics>,
  ): EasyRunDrift {
    val subject = sessions.easyOpenOn(today) ?: return EasyRunDrift.None

    // Same type and same planned intensity, judged by what the watch measured. A session with no
    // matched activity, or one synced before schema v9 and so carrying no intensity, is absent
    // rather than zero and is excluded — missing is not easy.
    val comparable =
      sessions
        .asSequence()
        .filter { it.id != subject.id }
        .filter { it.type == subject.type && it.intensity == subject.intensity }
        .filter { it.status == SessionStatus.COMPLETED }
        .filter { it.scheduledDate < today.toString() }
        .mapNotNull { session ->
          runMetricsBySession[session.id]?.intensityPercent?.let { session to it }
        }
        .sortedBy { (session, _) -> session.remindAtUtc }
        .toList()

    // Three to judge and three of baseline. Below that the median is an opinion about four runs,
    // and the same discipline applies as to a day the ring was not worn: no measurement, no advice.
    if (comparable.size < MINIMUM_COMPARABLE) return EasyRunDrift.None

    val median = comparable.map { (_, intensity) -> intensity }.median()
    val recent = comparable.takeLast(RECENT_RUNS).map { (_, intensity) -> intensity }
    if (recent.any { it <= median }) return EasyRunDrift.None

    return EasyRunDrift.Finding(
      sessionId = subject.id,
      type = subject.type,
      recentIntensityPercent = recent.reversed(),
      medianIntensityPercent = median.roundToInt(),
      comparableSessions = comparable.size,
    )
  }

  /**
   * The session this morning's card would sit next to: an easy one, today, still open.
   *
   * Still open because a session already run cannot be run differently, and this is a word before
   * the session rather than a verdict on it. The earliest of the day when there are two.
   */
  private fun List<TrainingSession>.easyOpenOn(date: LocalDate): TrainingSession? =
    filter {
      it.scheduledDate == date.toString() &&
        it.intensity == Intensity.EASY &&
        it.status.isOpen
    }
      .minByOrNull { it.remindAtUtc }

  /** The middle value, or the midpoint of the two middle ones. */
  private fun List<Int>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle].toDouble()
    else (sorted[middle - 1] + sorted[middle]) / 2.0
  }

  private companion object {

    /** Three in a row is the finding; one hard easy run is a Tuesday. */
    const val RECENT_RUNS = 3

    /** The three judged, plus three the median can be taken over. */
    const val MINIMUM_COMPARABLE = 6
  }
}
