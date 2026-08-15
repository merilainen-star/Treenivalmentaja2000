package fi.merilainen.treenivalmentaja.data.intervals

import com.squareup.moshi.Json

/**
 * One activity from `GET /api/v1/athlete/{id}/activities` — intervals.icu's `Activity` schema.
 *
 * **The real schema has 183 properties.** These are the dozen the app has a use for, and the
 * request names them through the `fields` query parameter so the other 171 are never sent. Every
 * field name and type here was read out of the vendored specification
 * (`docs/api/intervals-icu-openapi.json`), not from memory — the same discipline the Oura DTOs
 * were written under, and for the same reason: a Moshi mismatch in this project has shipped
 * before, compiling cleanly and failing on the phone.
 *
 * Every field is nullable because Moshi throws on a missing non-null one, and a service this app
 * does not control changing shape must become a row without data rather than a crash. Rows too
 * incomplete to use are dropped in [IntervalsMappers].
 */
internal data class IntervalsActivityDto(
  /** **A string**, unlike Strava's numeric id — e.g. `i84461234`. The app's primary key. */
  val id: String? = null,
  val name: String? = null,
  /** `Run`, `Ride`, `Walk`, `WeightTraining`, … The spec declares no enum, so it is not one here. */
  val type: String? = null,
  /** Local ISO-8601, e.g. `2026-08-15T06:12:03`. Carries no offset. */
  @Json(name = "start_date_local") val startDateLocal: String? = null,
  /** UTC ISO-8601. Preferred over the local one when present, because it is unambiguous. */
  @Json(name = "start_date") val startDate: String? = null,
  /** Seconds actually moving — what pace is computed from. */
  @Json(name = "moving_time") val movingTime: Int? = null,
  /** Seconds start to finish, pauses included. One more than [icuRecordingTime] in real data. */
  @Json(name = "elapsed_time") val elapsedTime: Int? = null,
  /**
   * Seconds the device recorded for — **the field that matches the watch's own total**.
   *
   * Measured against a real Suunto run on 2026-08-15: the watch reported 1:02:31 and this came
   * back as exactly 3751. `elapsed_time` was 3752 and the interval row said 3753, so of the three
   * near-identical totals this is the one to show.
   */
  @Json(name = "icu_recording_time") val icuRecordingTime: Int? = null,
  /**
   * Metres, as the recording device reported them.
   *
   * Both this and [icuDistance] are declared `number/float` with **no description** in the
   * specification, and nothing on intervals.icu's forum or docs explains how they differ. The
   * mapper therefore prefers `icu_distance` — the service's own figure for its own field — and
   * falls back to this, rather than picking one and pretending the choice was documented.
   */
  val distance: Double? = null,
  /** Metres. See [distance] for why both are fetched. */
  @Json(name = "icu_distance") val icuDistance: Double? = null,
  /**
   * Metres per second, and **the one anchored to the watch's own moving time**.
   *
   * Measured on a real run: `distance / average_speed` = 9520 / 3.096 = 3074.9 s = 51:14.9, which
   * is exactly the duration the Suunto reported. `pace` is a different speed for the same run —
   * `distance / moving_time` — so the two disagree by the 151 s intervals.icu adds when it
   * recomputes moving time from the stream. This is what lets the app show the numbers the runner
   * recognises without downloading a FIT file.
   */
  @Json(name = "average_speed") val averageSpeed: Double? = null,
  /** Metres per second. */
  @Json(name = "max_speed") val maxSpeed: Double? = null,
  @Json(name = "average_heartrate") val averageHeartrate: Int? = null,
  @Json(name = "max_heartrate") val maxHeartrate: Int? = null,
  /**
   * **Cycles** per minute, not steps — one leg.
   *
   * Measured: 81.228 for a run whose `average_stride` is 1.0899 m, and
   * `distance / (cadence × 2 × minutes)` reproduces that stride exactly while `× 1` does not. The
   * runner's own figure is therefore twice this, around 162 spm, and doubling happens at display
   * so the stored value stays the one the service sent.
   */
  @Json(name = "average_cadence") val averageCadence: Double? = null,
  @Json(name = "total_elevation_gain") val totalElevationGain: Double? = null,
  /** Kilocalories. Strava's summary endpoint carried none; this one does. */
  val calories: Int? = null,
  /**
   * intervals.icu's own training load for the activity — a number neither Oura nor Strava's
   * summary provides, and the one genuinely new measurement this integration brings.
   */
  @Json(name = "icu_training_load") val icuTrainingLoad: Int? = null,
  /**
   * How hard the effort was relative to threshold — the number that tells a *hard* 5 km from an
   * easy one of the same distance and duration.
   *
   * **The scale is undocumented.** The schema says `number/float` and gives no description, and
   * intervals.icu's own interface presents intensity as a percentage. It is therefore stored raw
   * and normalised only at the point of display; see `CompletedRunMetrics.intensityPercent`.
   */
  @Json(name = "icu_intensity") val icuIntensity: Double? = null,
  /**
   * Heart-rate-derived load, and `hr_load_type` says how — `HRSS` in the data seen so far.
   *
   * Equal to [icuTrainingLoad] on a run with no power meter, because that is where the load came
   * from. Kept separately anyway: they are the same number for a different reason, and a session
   * with power would separate them.
   */
  @Json(name = "hr_load") val hrLoad: Int? = null,
  /** Training impulse — the classic heart-rate integral, alongside intervals.icu's own load. */
  val trimp: Double? = null,
  /**
   * Where the activity came from. A documented enum: `STRAVA`, `UPLOAD`, `MANUAL`,
   * `GARMIN_CONNECT`, `OAUTH_CLIENT`, `DROPBOX`, `POLAR`, **`SUUNTO`**, `COROS`, `WAHOO`, `ZWIFT`,
   * `ZEPP`, `CONCEPT2`, `HUAWEI`.
   *
   * Stored because it is one column and it answers "did this really come off the watch", but
   * **not filtered on**: a run uploaded by hand is still that run, and hiding it would be the same
   * mistake as dropping an Oura workout whose activity word the app did not recognise.
   */
  val source: String? = null,
  /** The recording device, when the source knows one. Shown nowhere; kept for diagnostics. */
  @Json(name = "device_name") val deviceName: String? = null,
)

// There is no DTO for `/athlete/{id}/profile` here on purpose. Testing the key against a *different*
// endpoint would prove the key works for that endpoint; the connection test therefore asks the
// activities endpoint for one activity, which is the exact call the sync makes.
