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
    readiness: List<OuraReadinessDto>,
    sleep: List<OuraDailyScoreDto>,
    activity: List<OuraActivityDto>,
    fetchedAtUtc: Long,
    sleepPeriods: List<OuraSleepPeriodDto> = emptyList(),
  ): List<OuraDailySummaryEntity> {
    val readinessByDay = readiness.byDay { it.day }
    val sleepByDay = sleep.byDay { it.day }
    val activityByDay = activity.byDay { it.day }
    val nightByDay = nightsByDay(sleepPeriods)
    val days = readinessByDay.keys + sleepByDay.keys + activityByDay.keys + nightByDay.keys
    return days.sorted().map { day ->
      val night = nightByDay[day]
      val readinessDoc = readinessByDay[day]
      val activityDoc = activityByDay[day]
      val contributors = readinessDoc?.contributors
      OuraDailySummaryEntity(
        date = day,
        readinessScore = readinessDoc?.score,
        sleepScore = sleepByDay[day]?.score,
        activityScore = activityDoc?.score,
        averageHrvMs = night?.averageHrv?.takeIf { it > 0 },
        restingHrBpm = night?.lowestHeartRate?.takeIf { it > 0 },
        sleepHrBpm = night?.averageHeartRate?.takeIf { it > 0.0 }?.roundToInt(),
        // See ADR-014: a contributor score, not a measurement — kept apart from the bpm/ms columns
        // above by name as much as by the ADR that explains why the two must never merge.
        activityRecoveryTime = activityDoc?.contributors?.recoveryTime,
        readinessActivityBalance = contributors?.activityBalance,
        readinessBodyTemperature = contributors?.bodyTemperature,
        readinessHrvBalance = contributors?.hrvBalance,
        readinessPreviousDayActivity = contributors?.previousDayActivity,
        readinessPreviousNight = contributors?.previousNight,
        readinessRecoveryIndex = contributors?.recoveryIndex,
        readinessRestingHeartRate = contributors?.restingHeartRate,
        readinessSleepBalance = contributors?.sleepBalance,
        readinessSleepRegularity = contributors?.sleepRegularity,
        fetchedAtUtc = fetchedAtUtc,
      )
    }
  }

  /**
   * Groups any of the four per-day documents by [day], keeping the first per day.
   *
   * Generic over the DTO rather than duplicating this once per type: [OuraReadinessDto],
   * [OuraActivityDto] and [OuraDailyScoreDto] disagree on almost everything now that the first two
   * carry their own `contributors` shape, and the only thing this app still needs from all three
   * uniformly is "which day, and which document wins if Oura sent two" — see [toDailySummaries] for
   * why a repeated day keeps the first rather than being treated as a correction.
   */
  private fun <T> List<T>.byDay(day: (T) -> String?): Map<String, T> =
    mapNotNull { doc -> day(doc)?.takeIf { it.isNotBlank() }?.let { it to doc } }
      .groupBy({ it.first }) { it.second }
      .mapValues { (_, documents) -> documents.first() }

  /**
   * The one sleep period per day whose numbers describe *the night*.
   *
   * The sleep collection is the only one that returns several documents for the same day, because
   * naps are sleep periods too — and **averaging them together would be wrong**. A twenty-minute
   * nap's HRV is not a comparable measurement to a night's; blending them would quietly corrupt
   * exactly the trend this data was fetched to show. So one period is chosen and the rest ignored:
   *
   *  - `rest` and `deleted` are discarded outright. `rest` is a period Oura detected and the user
   *    rejected as not-sleep, and `deleted` is one they removed; treating either as a night would be
   *    reinstating data the person already said was wrong.
   *  - `long_sleep` wins — the spec's own definition is sleep long enough (>3 h) to contribute to
   *    the daily scores automatically, which is the night.
   *  - With no `long_sleep`, the longest remaining period wins. A night that came in under three
   *    hours is still that night's only measurement, and reporting nothing would be worse than
   *    reporting a short one. A day holding only a genuine nap yields that nap's numbers, which is
   *    the honest floor: it is what the ring measured while asleep that day.
   *
   * Periods with no `day` are dropped — the day is the row's primary key, and a reading that cannot
   * be addressed is worse than one that is not there.
   */
  private fun nightsByDay(periods: List<OuraSleepPeriodDto>): Map<String, OuraSleepPeriodDto> =
    periods
      .filter { !it.day.isNullOrBlank() && it.type !in IGNORED_SLEEP_TYPES }
      .groupBy { it.day!! }
      .mapValues { (_, forDay) ->
        forDay
          .sortedWith(
            compareByDescending<OuraSleepPeriodDto> { it.type == LONG_SLEEP }
              .thenByDescending { it.totalSleepDuration ?: 0 }
          )
          .first()
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
      // Fewer than a handful of samples is not a workout heart rate, and showing one as if it were
      // is worse than showing none. Measured case: Oura reported "heart rate data unavailable" for a
      // strength session, the window still held a couple of background samples, and the app printed
      // "syke 75" for a set of heavy squats.
      if (inside.size < MIN_HEART_RATE_SAMPLES) workout
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

  /** The `PublicSleepType` value for a period long enough to count as the night on its own. */
  private const val LONG_SLEEP = "long_sleep"

  /**
   * Periods that are not sleep the user accepts: one Oura detected and they rejected in the confirm
   * prompt, and one they deleted outright.
   */
  private val IGNORED_SLEEP_TYPES = setOf("rest", "deleted")

  /**
   * Below this, the samples in a workout's window are background readings rather than a record of
   * the effort — Oura samples continuously, so a window always contains *something*.
   */
  private const val MIN_HEART_RATE_SAMPLES = 5
}
