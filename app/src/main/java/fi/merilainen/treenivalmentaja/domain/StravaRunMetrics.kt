package fi.merilainen.treenivalmentaja.domain

/**
 * What Strava recorded for a session that was actually done — the running counterpart of
 * [CompletedSessionMetrics], and the reason Strava is integrated at all: pace.
 *
 * Oura sees a run as a duration and a distance; Strava carries the moving time the pace is
 * honestly computed from, plus the heart rate its recording device saw. Nullable measurements are
 * never rendered as zero.
 */
data class StravaRunMetrics(
  val activityId: Long,
  /** Strava's `SportType`, e.g. `Run`, `TrailRun`. */
  val sportType: String,
  val startTimeUtc: Long,
  /** Seconds actually moving — kept in seconds so the pace does not round through minutes. */
  val movingTimeSec: Long,
  val distanceKm: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
  val elevationGainMeters: Int? = null,
) {

  val movingMin: Int
    get() = (movingTimeSec / 60).toInt()

  /**
   * Seconds per kilometre, or `null` without a distance — never a pace computed against zero.
   *
   * From moving time, not elapsed: a pause at a crossing is not part of how fast the running was.
   * The 50-metre floor keeps a GPS blip from producing a pace of hours per kilometre.
   */
  val paceSecPerKm: Int?
    get() = distanceKm?.takeIf { it > 0.05 }?.let { (movingTimeSec / it).toInt() }

  /** `5:32 /km` — the form every runner reads pace in. */
  val paceText: String?
    get() = paceSecPerKm?.let { "%d:%02d /km".format(it / 60, it % 60) }
}
