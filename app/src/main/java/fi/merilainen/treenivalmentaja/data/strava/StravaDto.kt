package fi.merilainen.treenivalmentaja.data.strava

import com.squareup.moshi.Json

/**
 * One activity from `GET /api/v3/athlete/activities` — Strava's `SummaryActivity`.
 *
 * Every field nullable for the reason
 * [fi.merilainen.treenivalmentaja.data.oura.OuraDailyScoreDto]'s are: Moshi throws on a missing
 * non-null field, and a service this app does not control changing shape must become a row without
 * data, not a crash. Rows too incomplete to use are dropped in [StravaMappers].
 *
 * **`calories` is not here because the summary does not carry it.** Strava puts calories only on
 * `DetailedActivity`, one request per activity, against a rate budget there is no reason to spend —
 * a run's load is its pace, distance, time and heart rate, which the summary has.
 */
internal data class StravaActivityDto(
  val id: Long? = null,
  val name: String? = null,
  /** `Run`, `TrailRun`, `VirtualRun`, `Walk`, `Ride`, … — Strava's closed `SportType` enum. */
  @Json(name = "sport_type") val sportType: String? = null,
  /** UTC, e.g. `2026-08-15T06:12:03Z`. */
  @Json(name = "start_date") val startDate: String? = null,
  /** Seconds actually moving — the number pace is computed from. */
  @Json(name = "moving_time") val movingTime: Long? = null,
  /** Seconds from start to finish, pauses included. */
  @Json(name = "elapsed_time") val elapsedTime: Long? = null,
  /** Metres. */
  val distance: Double? = null,
  /** Metres per second. */
  @Json(name = "average_speed") val averageSpeed: Double? = null,
  @Json(name = "average_heartrate") val averageHeartrate: Double? = null,
  @Json(name = "max_heartrate") val maxHeartrate: Double? = null,
  /** Metres climbed. */
  @Json(name = "total_elevation_gain") val totalElevationGain: Double? = null,
)

/**
 * The token endpoint's success body.
 *
 * `expires_at` is absolute epoch seconds — Strava answers the "is this still good" question
 * directly, unlike Oura whose `expires_in` has to be added to a clock reading.
 */
internal data class StravaTokenResponseDto(
  @Json(name = "access_token") val accessToken: String? = null,
  @Json(name = "refresh_token") val refreshToken: String? = null,
  /** Epoch seconds. */
  @Json(name = "expires_at") val expiresAt: Long? = null,
)

/** Its failure body. Strava wraps field errors in a list; only the message is read. */
internal data class StravaTokenErrorDto(
  val message: String? = null,
)
