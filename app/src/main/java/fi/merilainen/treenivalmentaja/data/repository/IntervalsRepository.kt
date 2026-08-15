package fi.merilainen.treenivalmentaja.data.repository

import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsException
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsMappers
import fi.merilainen.treenivalmentaja.data.local.dao.IntervalsDao
import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsActivityEntity
import fi.merilainen.treenivalmentaja.domain.CompletedRunMetrics
import fi.merilainen.treenivalmentaja.domain.CompletedWorkout
import fi.merilainen.treenivalmentaja.domain.IntervalsActivityRef
import fi.merilainen.treenivalmentaja.domain.IntervalsRawResponse
import fi.merilainen.treenivalmentaja.domain.MatchOuraWorkoutsUseCase
import fi.merilainen.treenivalmentaja.domain.PlannedSession
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What an intervals.icu sync did, in the terms the screen that asked for it needs. */
sealed interface IntervalsSyncResult {

  data class Success(val activities: Int) : IntervalsSyncResult

  /**
   * @param retryAfterSeconds set only when the service asked for a specific wait, which is the
   *   one case where the app knows how long to hold off rather than guessing.
   */
  data class Failure(
    val message: String,
    val canRetry: Boolean,
    val retryAfterSeconds: Long? = null,
  ) : IntervalsSyncResult
}

/**
 * The one thing that writes to the intervals.icu table.
 *
 * The same contract as [OuraRepository]: Room is the source of truth, the screens observe the
 * database and never the network, and a failed sync changes nothing already stored.
 *
 * **The sync is idempotent by identity.** Rows are keyed on intervals.icu's own activity id and
 * upserted with `REPLACE`, so re-fetching an overlapping window — which every sync does, because
 * an activity can reach intervals.icu late — rewrites the same rows instead of creating new ones.
 * Nothing here compares start times or distances to decide whether two records are the same
 * activity; the service supplies a real identifier and guessing would be strictly worse.
 */
class IntervalsRepository internal constructor(
  private val client: IntervalsClient,
  private val dao: IntervalsDao,
  private val clock: () -> Long = System::currentTimeMillis,
  private val matcher: MatchOuraWorkoutsUseCase = MatchOuraWorkoutsUseCase(),
) {

  /** Reads intervals.icu between two dates and writes what came back. */
  suspend fun sync(from: LocalDate, to: LocalDate, zone: ZoneId): IntervalsSyncResult =
    try {
      val activities = client.activities(from, to)
      val rows = IntervalsMappers.toActivities(activities, clock(), zone)
      if (rows.isNotEmpty()) dao.upsertActivities(rows)
      IntervalsSyncResult.Success(activities = rows.size)
    } catch (e: IntervalsException) {
      IntervalsSyncResult.Failure(
        message = e.message ?: "Intervals.icu-tietojen haku epäonnistui.",
        canRetry = e.canRetry,
        retryAfterSeconds =
          (e as? fi.merilainen.treenivalmentaja.data.intervals.IntervalsRateLimitException)
            ?.retryAfterSeconds,
      )
    }

  /**
   * The activities response as the server sends it, for the diagnostics screen. **Stores nothing.**
   *
   * Separate from [sync] on purpose: that one asks eighteen named fields and writes rows, this one
   * asks for everything and writes nothing. A diagnostics call that quietly changed the database
   * would make the screen a source of the very confusion it exists to resolve.
   */
  suspend fun fetchRawActivities(from: LocalDate, to: LocalDate): IntervalsRawResponse =
    client.rawActivities(from, to)

  /** One activity in full, likewise stored nowhere. */
  suspend fun fetchRawActivity(activityId: String): IntervalsRawResponse =
    client.rawActivity(activityId)

  /**
   * The activities already synced, newest first, for the diagnostics picker.
   *
   * Read from the database rather than fetched, so choosing which activity to inspect costs no
   * request. These are by definition the ones the app knows about, which is also the honest set to
   * offer: an activity missing from here is itself a finding.
   */
  suspend fun recentActivityRefs(fromUtc: Long, toUtc: Long): List<IntervalsActivityRef> =
    dao.getActivitiesBetween(fromUtc, toUtc)
      .sortedByDescending { it.startTimeUtc }
      .map {
        IntervalsActivityRef(
          id = it.id,
          startTimeUtc = it.startTimeUtc,
          sportType = it.sportType,
          distanceMeters = it.distanceMeters,
        )
      }

  /**
   * Ties activities to the sessions they answer, and stores the result.
   *
   * The same matcher the Oura workouts go through — same day, nearest in time, and the activity
   * has to fit — so a `Run` can claim a running session and a `WeightTraining` a strength one, but
   * a `Walk` or a `Ride` claims nothing. Run after a sync, and safe to run again.
   *
   * Oura and the watch commonly record the *same* session. The two records attach independently
   * and the screens show both lines rather than guessing which device to believe.
   */
  suspend fun matchActivities(sessions: List<PlannedSession>, fromUtc: Long, toUtc: Long) {
    val activities = dao.getActivitiesBetween(fromUtc, toUtc)
    if (activities.isEmpty()) return
    val matches =
      matcher.execute(
        workouts =
          activities.map {
            CompletedWorkout(
              id = it.id,
              startTimeUtc = it.startTimeUtc,
              endTimeUtc = it.startTimeUtc + (it.elapsedTimeSec ?: it.movingTimeSec) * 1000,
              activityType = it.sportType,
            )
          },
        sessions = sessions,
      )
    for (activity in activities) {
      val sessionId = matches[activity.id]
      // Written even when it is null: a session moved out of an activity's reach must lose the
      // activity it used to claim.
      if (activity.matchedSessionId != sessionId) dao.setMatchedSession(activity.id, sessionId)
    }
  }

  /**
   * Every matched activity, keyed by the session it belongs to.
   *
   * Where a session somehow has two, the longer one wins — same rule as the Oura metrics, same
   * reason: the longer record is the one that looks like the session rather than like a walk to
   * the gym.
   */
  fun observeMatchedRunMetrics(): Flow<Map<String, CompletedRunMetrics>> =
    dao.observeMatchedActivities().map { rows ->
      rows
        .groupBy { it.matchedSessionId!! }
        .mapValues { (_, forSession) -> forSession.maxBy { it.movingTimeSec }.toMetrics() }
    }
}

private fun IntervalsActivityEntity.toMetrics(): CompletedRunMetrics =
  CompletedRunMetrics(
    activityId = id,
    sportType = sportType,
    startTimeUtc = startTimeUtc,
    movingTimeSec = movingTimeSec,
    recordingTimeSec = recordingTimeSec,
    distanceKm = distanceMeters?.let { it / 1000.0 },
    avgSpeedMps = avgSpeedMps,
    maxSpeedMps = maxSpeedMps,
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    avgCadence = avgCadence,
    elevationGainMeters = elevationGainMeters?.roundToInt(),
    calories = calories,
    trainingLoad = trainingLoad,
    intensity = intensity,
    hrLoad = hrLoad,
    trimp = trimp,
    deviceName = deviceName,
  )
