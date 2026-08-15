package fi.merilainen.treenivalmentaja.domain

import kotlin.math.roundToInt

/**
 * What the watch recorded for a session that was actually done — the counterpart of
 * [CompletedSessionMetrics], and the reason this integration exists: pace.
 *
 * Oura sees a run as a duration and a distance; the watch's own recording, which reaches the app
 * through intervals.icu, carries the moving time pace is honestly computed from, the heart rate
 * its sensor saw, and a training load neither Oura nor Strava's summary ever provided.
 *
 * Named for what it holds rather than for where it came from. It was `StravaRunMetrics` until
 * Strava paywalled its API in June 2026; the measurements did not change, only the road they
 * travel, and a type named after one supplier would have to be renamed at every such turn.
 */
data class CompletedRunMetrics(
  val activityId: String,
  /** The activity type as the source wrote it, e.g. `Run`, `TrailRun`. */
  val sportType: String,
  val startTimeUtc: Long,
  /** Seconds actually moving — kept in seconds so the pace does not round through minutes. */
  val movingTimeSec: Long,
  val distanceKm: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
  /** Steps per minute. */
  val avgCadence: Int? = null,
  val elevationGainMeters: Int? = null,
  val calories: Int? = null,
  /** intervals.icu's own training load for the activity, when it computed one. */
  val trainingLoad: Int? = null,
  /** Effort relative to threshold, raw from the service. Read through [intensityPercent]. */
  val intensity: Double? = null,
  /** The recording device, when the source named one. */
  val deviceName: String? = null,
) {

  val movingMin: Int
    get() = (movingTimeSec / 60).toInt()

  /**
   * Seconds per kilometre, or `null` without a distance — never a pace computed against zero.
   *
   * From moving time, not elapsed: a pause at a crossing is not part of how fast the running was.
   * The 50-metre floor keeps a GPS blip from producing a pace of hours per kilometre.
   *
   * Computed here rather than read from the service. intervals.icu does return a `pace` field, but
   * its unit is undocumented, and a number whose unit is a guess is worse than one derived from
   * two that are known.
   */
  val paceSecPerKm: Int?
    get() = distanceKm?.takeIf { it > 0.05 }?.let { (movingTimeSec / it).toInt() }

  /** `5:32 /km` — the form every runner reads pace in. */
  val paceText: String?
    get() = paceSecPerKm?.let { "%d:%02d /km".format(it / 60, it % 60) }

  /**
   * [intensity] as a whole percentage, which is how intervals.icu's own interface presents it.
   *
   * **The API's scale for this field is undocumented** — the schema declares `number/float` with
   * no description, and neither the integration cookbook nor the forum explains it. Services of
   * this kind report intensity either as a fraction of threshold (0.78) or as a percentage (78),
   * so this normalises: anything at or below [FRACTION_CEILING] is read as a fraction and scaled,
   * anything above it is already a percentage.
   *
   * The bound is what makes that safe rather than a coin flip. A training intensity above 300 %
   * of threshold is not a thing a person sustains for a session, and a *fraction* above 3.0 is
   * equally impossible, so no real value is ambiguous — either reading lands on the same number
   * intervals.icu itself would show.
   *
   * It lives here, in one property, precisely because it is an interpretation: the database keeps
   * the raw value, so if this reading ever turns out wrong it is one function to correct and no
   * stored data to migrate.
   */
  val intensityPercent: Int?
    get() =
      intensity?.let { if (it <= FRACTION_CEILING) (it * 100).roundToInt() else it.roundToInt() }

  private companion object {
    const val FRACTION_CEILING = 3.0
  }
}
