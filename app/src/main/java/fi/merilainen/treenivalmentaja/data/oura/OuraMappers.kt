package fi.merilainen.treenivalmentaja.data.oura

import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.local.entity.OuraWorkoutEntity
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

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
    )
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
