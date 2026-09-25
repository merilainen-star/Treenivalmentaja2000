package fi.merilainen.treenivalmentaja.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One lap of a run, as the watch itself recorded it.
 *
 * **Not a kilometre split.** A split is the app cutting the distance channel at every thousand
 * metres; a lap is a boundary the watch wrote into the recording — by a button press, or, in a
 * SuuntoPlus Guide session, at every step of the planned workout. For an interval session the laps
 * *are* the workout: a 6 × 400 m session recorded on 2026-09-22 has eighteen of them, one per
 * planned stage, and the six 400 m laps are the only place the repetition times exist. Kilometre
 * splits cut straight through the repetitions and average them into the recoveries around them.
 *
 * intervals.icu does not pass these on: it ran its own interval detection over that session and
 * found a single 40-minute "Recovery" interval. So the laps are read from the original file the
 * watch uploaded — see `FitLaps`.
 */
data class RunLap(
  /** 1-based, in the order the watch recorded them. */
  val index: Int,
  /**
   * The lap's timer time, in milliseconds — `total_timer_time`, the figure the watch shows in its
   * own lap table. Paused time is not in it.
   */
  val durationMs: Long,
  /** Metres. `null` when the recording carried no distance for the lap. */
  val distanceMeters: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
) {

  /** Seconds per kilometre over the lap's own distance; `null` for a lap too short to have one. */
  val paceSecPerKm: Int?
    get() =
      distanceMeters
        ?.takeIf { it >= MIN_PACE_METERS }
        ?.let { (durationMs / it).roundToInt() } // ms per metre == s per km

  /** `1:56,6` — tenths, because a 400 m repetition is judged on them. */
  val durationText: String
    get() = durationMs.formatLapTime()

  /** `400 m` under two kilometres, `2,15 km` above — the way the watch's own table writes it. */
  val distanceText: String?
    get() =
      distanceMeters?.let {
        if (it < 2000.0) "${it.roundToInt()} m"
        else String.format(java.util.Locale("fi", "FI"), "%.2f km", it / 1000.0)
      }
}

/** A lap shorter than this has no pace worth writing: GPS over 10 m is noise. */
private const val MIN_PACE_METERS = 10.0

/**
 * `1:56,6`, or `12:00,0`, or `1:02:31,0` — minutes and seconds with a decimal comma and tenths.
 *
 * Rounded to the nearest tenth rather than truncated, so 116 550 ms is 1:56,6 as the watch shows it.
 */
fun Long.formatLapTime(): String {
  val tenths = (this / 100.0).roundToLong()
  val totalSeconds = tenths / 10
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  val tenth = tenths % 10
  return if (hours > 0) "%d:%02d:%02d,%d".format(hours, minutes, seconds, tenth)
  else "%d:%02d,%d".format(minutes, seconds, tenth)
}

/**
 * The planned stage a lap answers, when the laps and the plan's stages can be paired one-to-one.
 *
 * They can when there are exactly as many laps as stages: a Guide session writes one lap per
 * stage, so equal counts is the signature of a session run from the plan. Any other count — a
 * manual lap pressed mid-run, a Guide abandoned half-way, a run recorded without the Guide — and
 * pairing by position would put the wrong target beside the wrong lap, which is worse than none.
 * The caller then shows the laps on their own.
 */
fun pairLapsWithSteps(laps: List<RunLap>, steps: List<RunStep>?): List<Pair<RunLap, RunStep?>> =
  if (steps != null && steps.isNotEmpty() && steps.size == laps.size) laps.zip(steps)
  else laps.map { it to null }

/**
 * What a stage asked for, in the unit the lap is judged in.
 *
 * A distance stage with a pace has a target *time* — 400 m at 4:25 /km is 1:46,0 — and that is the
 * number to hold a repetition against; a timed stage with a pace has a target pace. `null` when the
 * stage set no pace, which is most warm-ups and every recovery.
 */
fun RunStep.targetDurationMs(): Long? {
  val pace = paceSecPerKm ?: return null
  val metres = distanceMeters ?: return null
  return (metres.toLong() * pace)
}
