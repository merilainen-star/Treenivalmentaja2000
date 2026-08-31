package fi.merilainen.treenivalmentaja.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType

/** See `docs/DATA_MODEL.md` § 1. */
@Entity(tableName = "training_plans")
data class TrainingPlanEntity(
  @PrimaryKey val id: String,
  val name: String,
  /** `schemaVersion` of the JSON this plan was imported from. */
  val schemaVersion: Int,
  /** IANA zone id, e.g. `Europe/Helsinki`. */
  val timeZone: String,
  /** `YYYY-MM-DD`, local date in [timeZone]. */
  val startDate: String,
  val description: String? = null,
  val createdAt: Long,
  /** SHA-256 of the normalised source JSON. Used to tell a re-import from a conflicting edit. */
  val contentHash: String,
  val isActive: Boolean,
)

/** See `docs/DATA_MODEL.md` § 2. */
@Entity(
  tableName = "workout_sessions",
  foreignKeys =
    [
      ForeignKey(
        entity = TrainingPlanEntity::class,
        parentColumns = ["id"],
        childColumns = ["planId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices =
    [Index("planId"), Index("originalSessionId"), Index("scheduledDate"), Index("remindAtUtc")],
)
data class WorkoutSessionEntity(
  @PrimaryKey val id: String,
  val planId: String,
  val type: WorkoutType,
  val weekNumber: Int,
  /** `YYYY-MM-DD`, local date. */
  val scheduledDate: String,
  /** `HH:mm`, local time. */
  val scheduledTime: String?,
  /** Epoch millis UTC, resolved from date + time + the plan's time zone. */
  val remindAtUtc: Long,
  val timeIsFixed: Boolean = false,
  val reminderOverride: String? = null,
  val durationMin: Int? = null,
  val distanceKm: Double? = null,
  /** [fi.merilainen.treenivalmentaja.domain.Intensity] name, or `null`. */
  val intensity: String? = null,
  val rounds: Int? = null,
  val roundsMin: Int? = null,
  val roundsMax: Int? = null,
  val targetPace: String? = null,
  val warmupSec: Int? = null,
  val roundRestSec: Int? = null,
  /** JSON array of exercises; see `docs/DATA_MODEL.md` for why this is not normalised. */
  val exercisesJson: String? = null,
  val lighterAlternativeJson: String? = null,
  val description: String? = null,
  val status: SessionStatus,
  /** Stays true after the session later reaches `COMPLETED`. */
  val appliedLighterVariant: Boolean = false,
  /** Id of the session this one was rescheduled from; `null` for imported sessions. */
  val originalSessionId: String? = null,
  val updatedAt: Long,
)

/**
 * See `docs/DATA_MODEL.md` § 3.
 *
 * Immutable and append-only: rows are never updated and never deleted (the sole exception is the
 * cascade when the owning plan is removed). [fi.merilainen.treenivalmentaja.data.local.dao.SessionEventDao]
 * therefore exposes no update or delete method.
 */
@Entity(
  tableName = "session_events",
  foreignKeys =
    [
      ForeignKey(
        entity = WorkoutSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
      )
    ],
  indices = [Index("sessionId"), Index("timestampUtc")],
)
data class SessionEventEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val timestampUtc: Long,
  /** `null` only for the creation event. */
  val fromStatus: SessionStatus? = null,
  val toStatus: SessionStatus,
  val source: EventSource,
  val note: String? = null,
  val payloadJson: String? = null,
)

/** See `docs/DATA_MODEL.md` § 4. All scores are nullable — missing data is never treated as 0. */
@Entity(tableName = "oura_daily_summaries")
data class OuraDailySummaryEntity(
  /** `YYYY-MM-DD`. */
  @PrimaryKey val date: String,
  val readinessScore: Int? = null,
  val sleepScore: Int? = null,
  val activityScore: Int? = null,
  /**
   * The night's measurements, from the sleep-periods collection rather than the daily scores.
   *
   * Three columns from one request, and they sit on this row rather than in a table of their own
   * because Oura keys a sleep period by the day it *belongs to* — the morning you wake up — which is
   * exactly this row's primary key. No offset arithmetic, no join.
   *
   * `averageHrvMs` and `restingHrBpm` are the measurements behind `readinessScore`: the score is
   * Oura's opinion of the morning relative to this athlete's own baseline, these are the numbers it
   * formed that opinion from. Added at schema v11 for the AI analysis
   * ([ADR-010](../../../../../../../../docs/DECISIONS.md)), but useful to anything that wants a
   * trend rather than a verdict — rows written before v11 keep nulls, which is indistinguishable
   * from a night the ring was not worn, and correct in both cases.
   */
  val averageHrvMs: Int? = null,
  /** Oura's `lowest_heart_rate` — the resting figure its own app shows. */
  val restingHrBpm: Int? = null,
  /** Oura's `average_heart_rate` across the night. */
  val sleepHrBpm: Int? = null,
  /**
   * `daily_activity.contributors.recovery_time` — "contribution of previous 7-day recovery time",
   * 1..100. Oura's own "Recovery time" row under the Activity score, not [readinessScore] and not a
   * measurement: see ADR-014 in `docs/DECISIONS.md`. Added at schema v14.
   */
  val activityRecoveryTime: Int? = null,
  /**
   * The nine columns below are `daily_readiness.contributors` — Oura's own breakdown of why
   * [readinessScore] is what it is. All 1..100, all added at schema v14, all named `readiness*` so
   * none is ever mistaken for the bpm/ms measurements above: `readinessRestingHeartRate` is a
   * contribution score, [restingHrBpm] is the beats per minute. See ADR-014.
   */
  val readinessActivityBalance: Int? = null,
  val readinessBodyTemperature: Int? = null,
  val readinessHrvBalance: Int? = null,
  val readinessPreviousDayActivity: Int? = null,
  val readinessPreviousNight: Int? = null,
  val readinessRecoveryIndex: Int? = null,
  val readinessRestingHeartRate: Int? = null,
  val readinessSleepBalance: Int? = null,
  val readinessSleepRegularity: Int? = null,
  val fetchedAtUtc: Long,
)

/**
 * One activity read from intervals.icu — where the Suunto watch's own recordings arrive.
 *
 * Everything optional is nullable for the reason the Oura rows' fields are: a treadmill run may
 * carry no distance, heart rate exists only when a sensor was worn, and a walk has no training
 * load. Missing is never zero.
 *
 * `id` is intervals.icu's own activity id and **a string** (e.g. `i84461234`), which is what makes
 * the sync idempotent: a re-fetched activity overwrites itself rather than arriving twice.
 */
@Entity(
  tableName = "intervals_activities",
  indices = [Index("matchedSessionId"), Index("startTimeUtc")],
)
data class IntervalsActivityEntity(
  @PrimaryKey val id: String,
  val name: String? = null,
  /** intervals.icu's activity type, e.g. `Run`, `Walk`, `WeightTraining`. Stored as it arrives. */
  val sportType: String,
  val startTimeUtc: Long,
  /** Seconds intervals.icu counted as moving, recomputed by it from the stream. */
  val movingTimeSec: Long,
  /** Seconds start to finish, pauses included. */
  val elapsedTimeSec: Long? = null,
  /** `icu_recording_time` — the total that matches the watch's own. */
  val recordingTimeSec: Long? = null,
  val distanceMeters: Double? = null,
  /**
   * Metres per second, and the number the watch's own duration is recovered from:
   * `distanceMeters / avgSpeedMps`. Stored rather than that duration, because a speed is what the
   * service sent and the duration is a thing derived from it.
   */
  val avgSpeedMps: Double? = null,
  val maxSpeedMps: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
  /** **Cycles** per minute as the service sent it — one leg. Doubled at display, not here. */
  val avgCadence: Int? = null,
  val elevationGainMeters: Double? = null,
  val calories: Int? = null,
  /** intervals.icu's own training load — a number neither Oura nor Strava's summary provided. */
  val trainingLoad: Int? = null,
  /**
   * intervals.icu's effort relative to threshold, **stored exactly as the service sent it**.
   *
   * The scale is undocumented, so normalising on the way in would bake a guess into the database
   * where it could never be re-examined. Stored raw, interpreted at the point of display.
   */
  val intensity: Double? = null,
  /** Heart-rate-derived load. Equal to [trainingLoad] on a session with no power meter. */
  val hrLoad: Int? = null,
  /** Training impulse — the classic heart-rate integral. */
  val trimp: Double? = null,
  /**
   * Acute and chronic training load as they stood after this activity — fatigue and fitness.
   *
   * Stored because a use is named: the fatigue rule in `docs/ROADMAP.md` asks whether total load
   * has outrun what the plan assumed, and these two are what answers it. **Nothing reads them
   * yet**, and the accessor arrives with that rule rather than ahead of it.
   */
  val atl: Double? = null,
  val ctl: Double? = null,
  /**
   * Which service the activity came from: `SUUNTO`, `UPLOAD`, `MANUAL`, `STRAVA`, … A documented
   * enum, stored because it answers "did this really come off the watch". Never filtered on — a
   * run uploaded by hand is still that run.
   */
  val source: String? = null,
  val deviceName: String? = null,
  /** The planned session this activity answers, decided by the matcher — never by intervals.icu. */
  val matchedSessionId: String? = null,
  val fetchedAtUtc: Long,
)

/** See `docs/DATA_MODEL.md` § 5. */
@Entity(tableName = "oura_workouts", indices = [Index("matchedSessionId"), Index("startTimeUtc")])
data class OuraWorkoutEntity(
  /** Oura API id. */
  @PrimaryKey val id: String,
  val activityType: String,
  val startTimeUtc: Long,
  val endTimeUtc: Long,
  val calories: Float? = null,
  val matchedSessionId: String? = null,
  /** Metres, as Oura reports it. `null` for a workout with no distance. */
  val distanceMeters: Double? = null,
  /**
   * Beats per minute, averaged over the workout's own window.
   *
   * Not a field Oura returns on a workout — it has none. These are computed from the `heartrate`
   * time series between the workout's start and end, and stay `null` when that series is empty:
   * the `heartrate` scope was not granted, the ring is not one Oura serves it for, or it simply
   * recorded nothing. Missing is missing, never zero.
   */
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
)

/**
 * One day's training load, from intervals.icu's wellness record. See `docs/DATA_MODEL.md` § 7.
 *
 * **Keyed by date, and that is the whole point.** `intervals_activities` also stores `atl`/`ctl`,
 * but frozen at the moment of each activity — they never decay, so a three-day-old session reports
 * a fatigue that has since worn off. This table is the daily series, which is what "how loaded is
 * the athlete *today*" actually means.
 *
 * The activity columns are kept rather than removed: they are a true record of the load immediately
 * after that session, which is a different and legitimate fact. Nothing reads them for the analysis
 * any more.
 */
@Entity(tableName = "intervals_wellness")
data class IntervalsWellnessEntity(
  /** `YYYY-MM-DD`. */
  @PrimaryKey val date: String,
  /** Chronic training load — fitness. Nullable: a day before the athlete had any history has none. */
  val ctl: Double? = null,
  /** Acute training load — fatigue. */
  val atl: Double? = null,
  /** CTL change per week. */
  val rampRate: Double? = null,
  val fetchedAtUtc: Long,
)
