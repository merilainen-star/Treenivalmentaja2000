package fi.merilainen.treenivalmentaja.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the watch recorded for a session that was actually done — the counterpart of
 * [CompletedSessionMetrics], and the reason this integration exists.
 *
 * **Three durations, and they are all real.** A run measured on 2026-08-15 came back as 51:15
 * active, 53:46 moving and 1:02:31 total, and the app used to show only the middle one, which is
 * the one the runner had never seen. So all three are kept:
 *
 * | Duration | Where it comes from | Who recognises it |
 * | --- | --- | --- |
 * | [activeDurationSec] | `distance / average_speed` | **the watch** — its own Duration |
 * | [movingTimeSec] | `moving_time` | intervals.icu's web page |
 * | [recordingTimeSec] | `icu_recording_time` | the watch's Total time |
 *
 * The watch's own figure leads, because it is the number in the runner's memory from looking at
 * their wrist. The other two are shown beside it rather than instead of it: they differ for real
 * reasons — intervals.icu recomputes moving time from the stream and gets 151 s more — and hiding
 * the difference would turn a fact about two devices into an apparent bug.
 *
 * Named for what it holds rather than for where it came from. It was `StravaRunMetrics` until
 * Strava paywalled its API; the measurements did not change, only the road they travel.
 */
data class CompletedRunMetrics(
  val activityId: String,
  /** The activity type as the source wrote it, e.g. `Run`, `TrailRun`. */
  val sportType: String,
  val startTimeUtc: Long,
  /** Seconds intervals.icu counted as moving, recomputed by it from the stream. */
  val movingTimeSec: Long,
  /** `icu_recording_time` — the total that matches the watch's own. */
  val recordingTimeSec: Long? = null,
  val distanceKm: Double? = null,
  /** Metres per second, the watch's own average. [activeDurationSec] is recovered from it. */
  val avgSpeedMps: Double? = null,
  val maxSpeedMps: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
  /** **Cycles** per minute as the service sent it. Read through [stepsPerMinute]. */
  val avgCadence: Int? = null,
  val elevationGainMeters: Int? = null,
  val calories: Int? = null,
  /** intervals.icu's own training load for the activity, when it computed one. */
  val trainingLoad: Int? = null,
  /** Effort relative to threshold, raw from the service. Read through [intensityPercent]. */
  val intensity: Double? = null,
  /** Heart-rate-derived load. Equal to [trainingLoad] on a session with no power meter. */
  val hrLoad: Int? = null,
  /** Training impulse — the classic heart-rate integral. */
  val trimp: Double? = null,
  /** The recording device, when the source named one. */
  val deviceName: String? = null,
) {

  /**
   * The watch's own duration, recovered from distance and average speed.
   *
   * `average_speed` is anchored to the time the watch counted as moving, so dividing distance by
   * it gives that time back: 9520 m / 3.096 m/s = 3074.9 s = 51:14.9, against a Suunto that
   * reported 51:14.8. That is the whole reason this integration does not need to download a FIT
   * file to show the numbers a runner recognises.
   *
   * `null` without a speed or a distance — never a duration divided out of a zero.
   */
  val activeDurationSec: Long?
    get() {
      val speed = avgSpeedMps?.takeIf { it > 0.0 } ?: return null
      val metres = distanceKm?.takeIf { it > 0.05 }?.times(1000) ?: return null
      return (metres / speed).roundToLong()
    }

  /** The duration to lead with: the watch's own when it can be had, else what moving time says. */
  val primaryDurationSec: Long
    get() = activeDurationSec ?: movingTimeSec

  /**
   * Pace over [primaryDurationSec], so it is the pace the watch showed.
   *
   * **Rounded, not truncated.** The old version took the integer part, which turned 338.87 s/km
   * into 5:38 where intervals.icu and the watch both said 5:39 — a one-second disagreement that
   * looked like a different measurement rather than a different rounding.
   *
   * The 50-metre floor keeps a GPS blip from producing a pace of hours per kilometre.
   */
  val paceSecPerKm: Int?
    get() =
      distanceKm?.takeIf { it > 0.05 }?.let { (primaryDurationSec.toDouble() / it).roundToInt() }

  /** `5:32 /km` — the form every runner reads pace in. */
  val paceText: String?
    get() = paceSecPerKm?.formatPace()

  /** The fastest pace of the run, from `max_speed`. */
  val maxPaceText: String?
    get() = maxSpeedMps?.takeIf { it > 0.0 }?.let { (1000.0 / it).roundToInt().formatPace() }

  /**
   * Steps per minute — **twice** [avgCadence], which the service reports per leg.
   *
   * Measured: a run with `average_cadence` 81.228 has `average_stride` 1.0899 m, and
   * `distance / (cadence × 2 × minutes)` reproduces that stride exactly where `× 1` does not. A
   * runner reads cadence as something near 160–180, so the raw figure on screen looked like a
   * fault. Doubled here rather than in the database, so the stored value stays the one the service
   * sent.
   */
  val stepsPerMinute: Int?
    get() = avgCadence?.takeIf { it > 0 }?.times(2)

  /**
   * [intensity] as a whole percentage, which is how intervals.icu's own interface presents it.
   *
   * The API's scale for this field is undocumented — the schema declares `number/float` with no
   * description. Real data settled it: a run at 77 % came back as `77.13892`, already a
   * percentage. The fraction branch is kept anyway, because one account's data is not the
   * specification, and the bound makes it safe: a training intensity above 300 % of threshold and
   * a *fraction* above 3.0 are both impossible, so no real value is ambiguous.
   *
   * It lives here, in one property, precisely because it is an interpretation: the database keeps
   * the raw value, so if this reading is ever proved wrong it is one function to correct and no
   * stored data to migrate.
   */
  val intensityPercent: Int?
    get() =
      intensity?.let { if (it <= FRACTION_CEILING) (it * 100).roundToInt() else it.roundToInt() }

  private companion object {
    const val FRACTION_CEILING = 3.0
  }
}

/** `5:39 /km`. */
private fun Int.formatPace(): String = "%d:%02d /km".format(this / 60, this % 60)

/**
 * `51:15`, or `1:02:31` once it passes an hour.
 *
 * Hours only when there are hours: `0:51:15` for a run is how a stopwatch reads, not how a person
 * says it.
 */
fun Long.formatDuration(): String {
  val hours = this / 3600
  val minutes = (this % 3600) / 60
  val seconds = this % 60
  return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
  else "%d:%02d".format(minutes, seconds)
}
