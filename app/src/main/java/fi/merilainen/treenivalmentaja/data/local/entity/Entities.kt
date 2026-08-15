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
  /** Seconds actually moving — what pace is computed from. */
  val movingTimeSec: Long,
  /** Seconds start to finish, pauses included. */
  val elapsedTimeSec: Long? = null,
  val distanceMeters: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
  val elevationGainMeters: Double? = null,
  val calories: Int? = null,
  /** intervals.icu's own training load — a number neither Oura nor Strava's summary provided. */
  val trainingLoad: Int? = null,
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
