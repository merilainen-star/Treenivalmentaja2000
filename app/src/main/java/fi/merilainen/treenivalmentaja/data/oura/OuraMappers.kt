package fi.merilainen.treenivalmentaja.data.oura

import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.local.entity.OuraWorkoutEntity
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/**
 * Oura's documents as this app's rows.
 *
 * The one rule these all obey: **missing is not zero**. A day the ring was not worn comes back from
 * Oura as a document with no score, and it is stored as a row with no score. Nothing here
 * substitutes a number for an absence, and nothing downstream may either — see `docs/DATA_MODEL.md`
 * § 4 and `docs/API_INTEGRATIONS.md`.
 */
internal object OuraMappers {

  /**
   * Three collections into one row per day.
   *
   * Readiness, sleep and activity are separate documents on the same calendar day, and
   * `oura_daily_summaries` is keyed by that day. A day is represented if *any* of the three
   * mentioned it; the two the ring had nothing to say about stay `null` rather than becoming 0.
   *
   * Documents with no `day` are dropped: the day is the primary key, and a row that cannot be
   * addressed is worse than a row that is not there. A repeated day keeps the first document —
   * Oura returns one per collection per day, so a second is a service anomaly and not a correction
   * this app can rank.
   */
  fun toDailySummaries(
    readiness: List<OuraDailyScoreDto>,
    sleep: List<OuraDailyScoreDto>,
    activity: List<OuraDailyScoreDto>,
    fetchedAtUtc: Long,
  ): List<OuraDailySummaryEntity> {
    val byDay = { list: List<OuraDailyScoreDto> ->
      list
        .filter { !it.day.isNullOrBlank() }
        .groupBy { it.day!! }
        .mapValues { (_, documents) -> documents.first().score }
    }
    val readinessByDay = byDay(readiness)
    val sleepByDay = byDay(sleep)
    val activityByDay = byDay(activity)
    return (readinessByDay.keys + sleepByDay.keys + activityByDay.keys).sorted().map { day ->
      OuraDailySummaryEntity(
        date = day,
        readinessScore = readinessByDay[day],
        sleepScore = sleepByDay[day],
        activityScore = activityByDay[day],
        fetchedAtUtc = fetchedAtUtc,
      )
    }
  }

  /**
   * Workouts as rows, dropping the ones that cannot be placed in time.
   *
   * `matchedSessionId` is left `null` here. Deciding which planned session a workout answers to is
   * a training-domain question — a run at the right hour on the right day, not a field that arrives
   * from Oura — and it does not belong in a parser.
   */
  fun toWorkouts(workouts: List<OuraWorkoutDto>): List<OuraWorkoutEntity> =
    workouts.mapNotNull { it.toEntity() }

  /**
   * `null` when the row is too incomplete to store: no id, or a start or end this app cannot place
   * on the clock. The whole point of these rows is comparing them against planned sessions by time,
   * and a workout with no usable timestamp cannot take part in that.
   */
  private fun OuraWorkoutDto.toEntity(): OuraWorkoutEntity? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val start = epochMillis(startDatetime) ?: return null
    val end = epochMillis(endDatetime) ?: return null
    return OuraWorkoutEntity(
      id = id,
      // Free-form in the specification, so it is stored as it arrives rather than mapped onto this
      // app's WorkoutType — Oura's vocabulary is not this app's, and guessing between them here
      // would bury the guess in a parser.
      activityType = activity?.takeIf { it.isNotBlank() } ?: UNKNOWN_ACTIVITY,
      startTimeUtc = start,
      endTimeUtc = end,
      calories = calories?.toFloat(),
      matchedSessionId = null,
      distanceMeters = distance,
    )
  }

  /**
   * Fills each workout's average and maximum heart rate from a series of samples.
   *
   * Oura puts no heart rate on a workout, so this is the only way to have one: take the samples
   * that fall inside the workout's own window and reduce them. Samples are matched **by time
   * alone**, not by their `source` field — a treadmill hour the ring logged as `awake` is still
   * that hour's heart rate, and filtering on a label the app does not control would silently drop
   * it.
   *
   * A workout with no samples in its window keeps `null` on both, which is the honest answer when
   * the `heartrate` scope was never granted, the ring does not report it, or nothing was recorded.
   */
  fun withHeartRate(
    workouts: List<OuraWorkoutEntity>,
    samples: List<OuraHeartRateDto>,
  ): List<OuraWorkoutEntity> {
    if (workouts.isEmpty() || samples.isEmpty()) return workouts
    val beats =
      samples.mapNotNull { sample ->
        val at = epochMillis(sample.timestamp) ?: return@mapNotNull null
        val bpm = sample.bpm?.takeIf { it > 0 } ?: return@mapNotNull null
        at to bpm
      }
    if (beats.isEmpty()) return workouts
    return workouts.map { workout ->
      val inside = beats.filter { (at, _) -> at in workout.startTimeUtc..workout.endTimeUtc }
      if (inside.isEmpty()) workout
      else
        workout.copy(
          avgHeartRate = inside.sumOf { it.second }.toDouble().div(inside.size).roundToInt(),
          maxHeartRate = inside.maxOf { it.second },
        )
    }
  }

  /**
   * An Oura offset date-time as epoch millis.
   *
   * The offset is the one the workout happened in, and dropping it would move an evening session by
   * two or three hours depending on the season — precisely the kind of error that makes a matcher
   * pair a run with the wrong day.
   */
  private fun epochMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
      OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
      null
    }
  }

  private const val UNKNOWN_ACTIVITY = "unknown"
}
