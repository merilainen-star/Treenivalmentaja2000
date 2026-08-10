package fi.merilainen.treenivalmentaja.domain

/**
 * What Oura recorded for a session that was actually done.
 *
 * The plan says what was asked for; this says what happened. Every measurement is nullable except
 * the ones Oura guarantees, for the usual reason — a workout carries no heart rate unless the ring
 * reported one, and a strength session has no distance at all. Nothing here is rendered when it is
 * absent, rather than rendered as zero.
 */
data class CompletedSessionMetrics(
  val ouraWorkoutId: String,
  /** Oura's own word for the activity, e.g. `running`. Free-form, shown only as a fallback. */
  val activityType: String,
  val startTimeUtc: Long,
  val durationMin: Int,
  val calories: Int? = null,
  val distanceKm: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
) {

  /** True when Oura recorded the workout but nothing worth showing beyond that it happened. */
  val hasNumbers: Boolean
    get() = calories != null || distanceKm != null || avgHeartRate != null
}
