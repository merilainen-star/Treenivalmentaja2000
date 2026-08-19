package fi.merilainen.treenivalmentaja.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.local.entity.OuraWorkoutEntity
import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsActivityEntity
import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsWellnessEntity
import fi.merilainen.treenivalmentaja.data.local.entity.SessionEventEntity
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingPlanDao {
  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(plan: TrainingPlanEntity)

  /**
   * A real UPDATE, deliberately — never `@Insert(onConflict = REPLACE)`.
   *
   * Room implements REPLACE as DELETE followed by INSERT, which fires `ON DELETE CASCADE` on the
   * sessions and their events. Correcting a plan in place would then silently destroy the exact
   * history it exists to preserve.
   */
  @Update suspend fun update(plan: TrainingPlanEntity)

  @Query("SELECT * FROM training_plans WHERE isActive = 1 LIMIT 1")
  fun observeActivePlan(): Flow<TrainingPlanEntity?>

  @Query("SELECT * FROM training_plans WHERE isActive = 1 LIMIT 1")
  suspend fun getActivePlan(): TrainingPlanEntity?

  @Query("SELECT id FROM training_plans WHERE isActive = 1 LIMIT 1")
  suspend fun getActivePlanId(): String?

  @Query("SELECT * FROM training_plans WHERE id = :id")
  suspend fun getById(id: String): TrainingPlanEntity?

  @Query("SELECT COUNT(*) FROM training_plans") suspend fun count(): Int

  @Query("UPDATE training_plans SET isActive = 0") suspend fun deactivateAll()

  @Query("DELETE FROM training_plans WHERE id = :id") suspend fun deleteById(id: String)

  /** Plans an import has superseded. Sessions and their events cascade away with them. */
  @Query("DELETE FROM training_plans WHERE isActive = 0") suspend fun deleteInactive(): Int
  @Query("DELETE FROM training_plans") suspend fun deleteAll()
}

@Dao
interface WorkoutSessionDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertAll(sessions: List<WorkoutSessionEntity>)

  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(session: WorkoutSessionEntity)

  @Update suspend fun update(session: WorkoutSessionEntity)

  @Query(
    """
    SELECT s.* FROM workout_sessions s
    INNER JOIN training_plans p ON p.id = s.planId
    WHERE p.isActive = 1
    ORDER BY s.remindAtUtc ASC, s.id ASC
    """
  )
  fun observeActivePlanSessions(): Flow<List<WorkoutSessionEntity>>

  @Query("SELECT * FROM workout_sessions WHERE id = :id")
  suspend fun getById(id: String): WorkoutSessionEntity?

  @Query("SELECT * FROM workout_sessions WHERE status = :status")
  suspend fun getByStatus(status: SessionStatus): List<WorkoutSessionEntity>

  /**
   * The same, restricted to the plan currently in use.
   *
   * Importing a plan deactivates the previous one but leaves its rows in place, so [getByStatus]
   * still returns sessions belonging to plans the user has replaced. Scheduling alarms from that
   * list makes a superseded programme keep sending reminders alongside the current one.
   */
  @Query(
    """
    SELECT s.* FROM workout_sessions s
    INNER JOIN training_plans p ON p.id = s.planId
    WHERE p.isActive = 1 AND s.status = :status
    """
  )
  suspend fun getByStatusInActivePlan(status: SessionStatus): List<WorkoutSessionEntity>

  @Query("SELECT * FROM workout_sessions WHERE planId = :planId ORDER BY remindAtUtc ASC")
  suspend fun getByPlan(planId: String): List<WorkoutSessionEntity>

  @Query("SELECT * FROM workout_sessions WHERE planId = :planId AND scheduledDate >= :date ORDER BY remindAtUtc ASC")
  suspend fun getByPlanFromDate(planId: String, date: String): List<WorkoutSessionEntity>

  @Query("SELECT * FROM workout_sessions WHERE planId = :planId AND status = :status ORDER BY remindAtUtc ASC")
  suspend fun getByPlanAndStatus(planId: String, status: SessionStatus): List<WorkoutSessionEntity>

  /** Which of [ids] already exist. Used by the importer to report duplicates before writing. */
  @Query("SELECT id FROM workout_sessions WHERE id IN (:ids)")
  suspend fun existingIds(ids: List<String>): List<String>

  @Query("SELECT COUNT(*) FROM workout_sessions") suspend fun count(): Int
  @Query("DELETE FROM workout_sessions") suspend fun deleteAll()
}

/**
 * Append-only. There is deliberately no `@Update` and no `@Delete` here — the event log is the
 * audit trail and must never be rewritten. See `docs/DATA_MODEL.md` § 3.
 */
@Dao
interface SessionEventDao {
  @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(event: SessionEventEntity)

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insertAll(events: List<SessionEventEntity>)

  @Query(
    "SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY timestampUtc ASC, id ASC"
  )
  fun observeForSession(sessionId: String): Flow<List<SessionEventEntity>>

  @Query(
    "SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY timestampUtc ASC, id ASC"
  )
  suspend fun getForSession(sessionId: String): List<SessionEventEntity>

  @Query("SELECT COUNT(*) FROM session_events WHERE sessionId = :sessionId")
  suspend fun countForSession(sessionId: String): Int

  @Query("DELETE FROM session_events")
  suspend fun deleteAll()
}

@Dao
interface OuraDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDailySummary(summary: OuraDailySummaryEntity)

  /**
   * A whole sync's worth of days at once.
   *
   * `REPLACE` is safe here in a way it is not on `training_plans`: nothing references these rows, so
   * the delete-then-insert Room compiles it into cascades to nothing. A re-fetched day overwrites
   * the day it re-fetched, which is the intent — Oura revises a score as the night is processed.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDailySummaries(summaries: List<OuraDailySummaryEntity>)

  @Query("SELECT * FROM oura_daily_summaries WHERE date = :date")
  fun observeDailySummary(date: String): Flow<OuraDailySummaryEntity?>

  @Query("SELECT * FROM oura_daily_summaries WHERE date BETWEEN :fromDate AND :toDate")
  fun observeDailySummaries(fromDate: String, toDate: String): Flow<List<OuraDailySummaryEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertWorkouts(workouts: List<OuraWorkoutEntity>)

  @Query("SELECT * FROM oura_workouts WHERE startTimeUtc BETWEEN :fromUtc AND :toUtc")
  suspend fun getWorkoutsBetween(fromUtc: Long, toUtc: Long): List<OuraWorkoutEntity>

  /**
   * Ties a completed Oura workout to the session it answers.
   *
   * A `@Query` rather than an update of the whole row: matching runs after a sync has already
   * written the row, and re-writing it here would risk putting back a stale copy of everything
   * else it holds.
   */
  @Query("UPDATE oura_workouts SET matchedSessionId = :sessionId WHERE id = :workoutId")
  suspend fun setMatchedSession(workoutId: String, sessionId: String?)

  @Query("SELECT * FROM oura_workouts WHERE matchedSessionId = :sessionId ORDER BY startTimeUtc ASC")
  fun observeWorkoutsForSession(sessionId: String): Flow<List<OuraWorkoutEntity>>

  /** Every workout tied to any session, for the screens that draw a list of them. */
  @Query("SELECT * FROM oura_workouts WHERE matchedSessionId IS NOT NULL")
  fun observeMatchedWorkouts(): Flow<List<OuraWorkoutEntity>>

  /**
   * Workouts Oura recorded that belong to no planned session.
   *
   * A spontaneous walk, or one the matcher could not place. Shown rather than hidden: the app
   * having fetched something and then saying nothing about it is indistinguishable, from the
   * outside, from never having fetched it.
   */
  @Query(
    """
    SELECT * FROM oura_workouts
    WHERE matchedSessionId IS NULL AND startTimeUtc BETWEEN :fromUtc AND :toUtc
    ORDER BY startTimeUtc ASC
    """
  )
  fun observeUnmatchedWorkouts(fromUtc: Long, toUtc: Long): Flow<List<OuraWorkoutEntity>>

  @Query("DELETE FROM oura_daily_summaries") suspend fun clearDailySummaries()

  @Query("DELETE FROM oura_workouts") suspend fun clearWorkouts()
}

@Dao
interface IntervalsDao {

  /**
   * `REPLACE` is safe for the reason it is on the Oura tables: nothing references these rows.
   *
   * It is also what makes the sync idempotent. The primary key is intervals.icu's own activity id,
   * so re-fetching an overlapping window rewrites the same rows rather than duplicating them —
   * dedup by identity, never by comparing a start time and a distance.
   */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertActivities(activities: List<IntervalsActivityEntity>)

  @Query("SELECT * FROM intervals_activities WHERE startTimeUtc BETWEEN :fromUtc AND :toUtc")
  suspend fun getActivitiesBetween(fromUtc: Long, toUtc: Long): List<IntervalsActivityEntity>

  /** Same shape as the Oura matcher write: only the link, never the whole row. */
  @Query("UPDATE intervals_activities SET matchedSessionId = :sessionId WHERE id = :activityId")
  suspend fun setMatchedSession(activityId: String, sessionId: String?)

  /** Every activity tied to any session, for the screens that draw a list of them. */
  @Query("SELECT * FROM intervals_activities WHERE matchedSessionId IS NOT NULL")
  fun observeMatchedActivities(): Flow<List<IntervalsActivityEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertWellness(days: List<IntervalsWellnessEntity>)

  /**
   * The most recent load figures on or before a date.
   *
   * "On or before" rather than "on", because a day can be missing — intervals.icu writes a wellness
   * record when it has something to say, and the analysis still needs the athlete's current state
   * rather than nothing at all. Ordering by date descending and taking one gives the freshest
   * record that is not in the session's future.
   *
   * Rows with no load at all are skipped: a wellness record can exist carrying only a sleep score,
   * and returning it would answer "the athlete has no fitness" rather than "we do not know".
   */
  @Query(
    "SELECT * FROM intervals_wellness WHERE date <= :onOrBefore AND (ctl IS NOT NULL OR atl IS NOT NULL) ORDER BY date DESC LIMIT 1"
  )
  suspend fun latestWellnessOnOrBefore(onOrBefore: String): IntervalsWellnessEntity?

  @Query("DELETE FROM intervals_wellness") suspend fun clearWellness()

  @Query("DELETE FROM intervals_activities") suspend fun clearActivities()
}
