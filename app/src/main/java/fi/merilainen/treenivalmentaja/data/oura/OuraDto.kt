package fi.merilainen.treenivalmentaja.data.oura

import com.squareup.moshi.Json

/**
 * The envelope every collection answers with: `{"data": [...], "next_token": "..." | null}`.
 *
 * `next_token` is `null` on the last page and is fed back as a query parameter to get the next
 * one. Both fields are declared required by the specification and both are nullable here anyway —
 * see [OuraDailyScoreDto] for why.
 */
internal data class OuraPageDto<T>(
  val data: List<T?>? = null,
  @Json(name = "next_token") val nextToken: String? = null,
)

/**
 * One day's score, for `daily_readiness`, `daily_sleep` and `daily_activity` alike.
 *
 * One class for three collections because the three documents agree on exactly the fields this app
 * reads: `id`, `day`, `score`, `timestamp`. What differs is everything it does not read —
 * readiness carries temperature deviations, activity carries twenty-five fields of step counts and
 * MET minutes — and Moshi ignores unknown fields, so a fourth column would be a change here rather
 * than a new type.
 *
 * **`score` is nullable in the specification and that is not defensive coding.** A day the ring was
 * not worn comes back as a document *with no score*, not as no document. Anything downstream has to
 * be able to say "ei tietoa" about a day that exists — see `docs/API_INTEGRATIONS.md`.
 *
 * The rest are nullable because a DTO that cannot be constructed is a crash: Moshi throws on a
 * missing non-null field, so declaring `id` non-null would turn any future change in a service this
 * app does not control into a crash instead of a day without data. Rows too incomplete to use are
 * dropped in [OuraMappers] rather than at parse time.
 */
internal data class OuraDailyScoreDto(
  val id: String? = null,
  /** `YYYY-MM-DD`, the day the document belongs to. */
  val day: String? = null,
  /** 1..100, or `null` for a day the ring was not worn. Never read as zero. */
  val score: Int? = null,
  val timestamp: String? = null,
)

/**
 * One sleep period, `PublicModifiedSleepModel` — the night itself, not its score.
 *
 * **This is where the nightly measurements live.** The `daily_sleep` collection carries a 0–100
 * score and nothing else; `average_hrv` and `lowest_heart_rate` exist only here. Three fields are
 * read of the roughly thirty the schema declares, and taking all three costs nothing extra — they
 * arrive in the same document.
 *
 * The specification declares only `id`, `bedtime_start`, `bedtime_end`, `day`, `low_battery_alert`,
 * `period` and `time_in_bed` as required; everything this app reads is explicitly nullable there,
 * which is the honest shape for a night the ring was charging or a period the algorithm could not
 * score. Nullable here for the usual reason on top of that: Moshi throws on a missing non-null
 * field, so a service changing shape must become a row without data rather than a crash.
 */
internal data class OuraSleepPeriodDto(
  val id: String? = null,
  /**
   * `YYYY-MM-DD` — **the day the sleep belongs to**, which is the morning you wake up.
   *
   * That is already how `oura_daily_summaries` is keyed, so the night merges onto the existing row
   * with no offset arithmetic. Worth stating because the opposite convention would be just as
   * plausible and would silently shift every reading by a day.
   */
  val day: String? = null,
  /**
   * Which kind of period this is: `long_sleep`, `sleep`, `late_nap`, `rest`, `deleted`.
   *
   * The field that makes this collection usable. A day can hold a night *and* a nap, and their
   * numbers are not comparable — see [OuraMappers] for which one wins and why averaging would be
   * wrong.
   */
  val type: String? = null,
  /** Average heart-rate variability during sleep, in milliseconds. */
  @Json(name = "average_hrv") val averageHrv: Int? = null,
  /**
   * Lowest heart rate during sleep — the resting figure Oura's own app shows.
   *
   * The spec notes this is computed from 30-second samples and so differs slightly from the app's
   * own display, which aggregates to 5 minutes. Close enough to be the same measurement; recorded
   * here so a future discrepancy is not mistaken for a bug.
   */
  @Json(name = "lowest_heart_rate") val lowestHeartRate: Int? = null,
  /** Average heart rate during sleep, beats per minute. Same 30-second-sample caveat. */
  @Json(name = "average_heart_rate") val averageHeartRate: Double? = null,
  /** Seconds actually asleep. Used only to rank periods when no `long_sleep` is present. */
  @Json(name = "total_sleep_duration") val totalSleepDuration: Int? = null,
)

/**
 * One heart-rate sample, `PublicHeartRateRow`.
 *
 * A separate collection from everything else, and a different request shape: it is a time series
 * asked for with `start_datetime`/`end_datetime` rather than dates. [source] says what the ring was
 * doing — `workout`, `sleep`, `rest`, `awake`, `session`, `live` — which is what makes it possible
 * to keep only the samples that belong to a workout.
 */
internal data class OuraHeartRateDto(
  /** An offset date-time. */
  val timestamp: String? = null,
  val bpm: Int? = null,
  val source: String? = null,
)

/**
 * One completed workout, `PublicWorkout`.
 *
 * The field names are `activity` and `calories`. An earlier revision of `docs/API_INTEGRATIONS.md`
 * called them `activity_type` and `active_calories`; neither exists in the specification, and a
 * Moshi mismatch in this project has shipped before — compiling cleanly and failing on the phone.
 *
 * Required by the spec: `id`, `activity`, `day`, `end_datetime`, `intensity`, `source`,
 * `start_datetime`. Optional: `calories`, `distance`, `label`.
 */
internal data class OuraWorkoutDto(
  val id: String? = null,
  /** Free-form activity name, e.g. `running`. Not an enum in the specification. */
  val activity: String? = null,
  /** `YYYY-MM-DD`. */
  val day: String? = null,
  /** An offset date-time, e.g. `2026-08-09T17:00:00+03:00`. */
  @Json(name = "start_datetime") val startDatetime: String? = null,
  @Json(name = "end_datetime") val endDatetime: String? = null,
  /** Kilocalories. */
  val calories: Double? = null,
  /** Metres. */
  val distance: Double? = null,
  /** `easy`, `moderate` or `hard`. */
  val intensity: String? = null,
  /** `manual`, `autodetected`, `confirmed` or `workout_heart_rate` — where the workout came from. */
  val source: String? = null,
  val label: String? = null,
)
