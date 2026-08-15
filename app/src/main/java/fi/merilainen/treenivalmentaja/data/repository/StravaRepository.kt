package fi.merilainen.treenivalmentaja.data.repository

import fi.merilainen.treenivalmentaja.data.local.dao.StravaDao
import fi.merilainen.treenivalmentaja.data.local.entity.StravaActivityEntity
import fi.merilainen.treenivalmentaja.data.strava.StravaClient
import fi.merilainen.treenivalmentaja.data.strava.StravaException
import fi.merilainen.treenivalmentaja.data.strava.StravaMappers
import fi.merilainen.treenivalmentaja.domain.CompletedWorkout
import fi.merilainen.treenivalmentaja.domain.MatchOuraWorkoutsUseCase
import fi.merilainen.treenivalmentaja.domain.PlannedSession
import fi.merilainen.treenivalmentaja.domain.StravaRunMetrics
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** What a Strava sync did, in the terms the screen that asked for it needs. */
sealed interface StravaSyncResult {

  data class Success(val activities: Int) : StravaSyncResult

  data class Failure(val message: String, val canRetry: Boolean) : StravaSyncResult
}

/**
 * The one thing that writes to the Strava table.
 *
 * The same contract as [OuraRepository]: Room is the source of truth, the screens observe the
 * database and never the network, and a failed sync changes nothing already stored.
 */
class StravaRepository internal constructor(
  private val client: StravaClient,
  private val dao: StravaDao,
  private val clock: () -> Long = System::currentTimeMillis,
  private val matcher: MatchOuraWorkoutsUseCase = MatchOuraWorkoutsUseCase(),
) {

  /** Reads Strava between two dates and writes what came back. */
  suspend fun sync(from: LocalDate, to: LocalDate, zone: ZoneId): StravaSyncResult =
    try {
      val activities = client.activities(from, to, zone)
      val rows = StravaMappers.toActivities(activities, clock())
      if (rows.isNotEmpty()) dao.upsertActivities(rows)
      StravaSyncResult.Success(activities = rows.size)
    } catch (e: StravaException) {
      StravaSyncResult.Failure(
        message = e.message ?: "Strava-tietojen haku epäonnistui.",
        canRetry = e.canRetry,
      )
    }

  /**
   * Ties Strava activities to the sessions they answer, and stores the result.
   *
   * The same matcher the Oura workouts go through — same day, nearest in time, and the activity
   * has to fit — so a Strava `Run` can claim a running session and a `WeightTraining` a strength
   * one, but a `Walk` or a `Ride` claims nothing. Run after a sync, and safe to run again.
   *
   * Oura and Strava commonly record the *same* run — the ring and the watch were both worn. The
   * two records attach to the same session independently, and the screens show Oura's line and
   * Strava's line side by side rather than guessing which device to believe.
   */
  suspend fun matchActivities(sessions: List<PlannedSession>, fromUtc: Long, toUtc: Long) {
    val activities = dao.getActivitiesBetween(fromUtc, toUtc)
    if (activities.isEmpty()) return
    val matches =
      matcher.execute(
        workouts =
          activities.map {
            CompletedWorkout(
              id = it.id.toString(),
              startTimeUtc = it.startTimeUtc,
              endTimeUtc = it.startTimeUtc + (it.elapsedTimeSec ?: it.movingTimeSec) * 1000,
              activityType = it.sportType,
            )
          },
        sessions = sessions,
      )
    for (activity in activities) {
      val sessionId = matches[activity.id.toString()]
      if (activity.matchedSessionId != sessionId) dao.setMatchedSession(activity.id, sessionId)
    }
  }

  /**
   * Every matched activity, keyed by the session it belongs to.
   *
   * Where a session somehow has two, the longer one wins — same rule as the Oura metrics, same
   * reason: the longer record is the one that looks like the session.
   */
  fun observeMatchedRunMetrics(): Flow<Map<String, StravaRunMetrics>> =
    dao.observeMatchedActivities().map { rows ->
      rows
        .groupBy { it.matchedSessionId!! }
        .mapValues { (_, forSession) -> forSession.maxBy { it.movingTimeSec }.toMetrics() }
    }
}

private fun StravaActivityEntity.toMetrics(): StravaRunMetrics =
  StravaRunMetrics(
    activityId = id,
    sportType = sportType,
    startTimeUtc = startTimeUtc,
    movingTimeSec = movingTimeSec,
    distanceKm = distanceMeters?.let { it / 1000.0 },
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
    elevationGainMeters = elevationGainMeters?.roundToInt(),
  )
