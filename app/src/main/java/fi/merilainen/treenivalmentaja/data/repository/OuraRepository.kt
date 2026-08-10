package fi.merilainen.treenivalmentaja.data.repository

import fi.merilainen.treenivalmentaja.data.local.dao.OuraDao
import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.local.entity.OuraWorkoutEntity
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraException
import fi.merilainen.treenivalmentaja.data.oura.OuraMappers
import fi.merilainen.treenivalmentaja.data.oura.OuraWorkoutDto
import fi.merilainen.treenivalmentaja.domain.CompletedSessionMetrics
import fi.merilainen.treenivalmentaja.domain.CompletedWorkout
import fi.merilainen.treenivalmentaja.domain.DailyRecovery
import fi.merilainen.treenivalmentaja.domain.MatchOuraWorkoutsUseCase
import fi.merilainen.treenivalmentaja.domain.OuraDiagnostics
import fi.merilainen.treenivalmentaja.domain.PlannedSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

/** What a sync did, in the terms the screen that asked for it needs. */
sealed interface OuraSyncResult {

  data class Success(val days: Int, val workouts: Int) : OuraSyncResult

  /**
   * Carries the failure's own Finnish message and whether waiting would help, so a caller neither
   * re-reads status codes nor writes its own wording.
   */
  data class Failure(val message: String, val canRetry: Boolean) : OuraSyncResult
}

/**
 * The one thing that writes to the Oura tables.
 *
 * Room is the source of truth (ADR-003 in `docs/DECISIONS.md`): the screens observe the database and
 * never the network, so what is on the phone survives being offline and a failed sync changes
 * nothing that is already stored.
 *
 * Nothing here decides *when* to sync. That belongs to whatever asked — the screen opening, or the
 * background worker.
 */
class OuraRepository internal constructor(
  private val client: OuraClient,
  private val dao: OuraDao,
  private val clock: () -> Long = System::currentTimeMillis,
  private val matcher: MatchOuraWorkoutsUseCase = MatchOuraWorkoutsUseCase(),
) {

  /**
   * Reads Oura between two dates and writes what came back.
   *
   * The four collections are fetched before anything is written, so a failure part-way leaves the
   * database as it was rather than half-updated with one collection's view of a day.
   *
   * A failure is returned rather than thrown: syncing is something the app does on its own
   * initiative, and an exception escaping into a `LaunchedEffect` would be a crash for a network
   * that was merely unavailable.
   */
  suspend fun sync(from: LocalDate, to: LocalDate): OuraSyncResult =
    try {
      val readiness = client.readiness(from, to)
      val sleep = client.sleep(from, to)
      val activity = client.activity(from, to)
      val workouts = client.workouts(from, to)

      val fetchedAt = clock()
      val summaries = OuraMappers.toDailySummaries(readiness, sleep, activity, fetchedAt)
      val workoutRows = withHeartRatePerWorkout(OuraMappers.toWorkouts(workouts))

      if (summaries.isNotEmpty()) dao.upsertDailySummaries(summaries)
      if (workoutRows.isNotEmpty()) dao.upsertWorkouts(workoutRows)

      OuraSyncResult.Success(days = summaries.size, workouts = workoutRows.size)
    } catch (e: OuraException) {
      OuraSyncResult.Failure(
        message = e.message ?: "Oura-tietojen haku epäonnistui.",
        canRetry = e.canRetry,
      )
    }

  /**
   * Asks Oura the same questions a sync asks, and reports what came back rather than storing it.
   *
   * Each collection is caught on its own, so one failing does not hide the others' answers — the
   * whole point is to tell "Oura returned nothing" apart from "we never asked" and from "it failed".
   * Nothing here writes to the database.
   */
  suspend fun diagnose(from: LocalDate, to: LocalDate): OuraDiagnostics {
    val failures = mutableListOf<String>()

    suspend fun <T> attempt(label: String, block: suspend () -> List<T>): List<T> =
      try {
        block()
      } catch (e: OuraException) {
        failures += "$label: ${e.message}"
        emptyList()
      }

    val readiness = attempt("Palautuminen") { client.readiness(from, to) }
    val sleep = attempt("Uni") { client.sleep(from, to) }
    val activity = attempt("Aktiivisuus") { client.activity(from, to) }
    val workouts = attempt("Treenit") { client.workouts(from, to) }
    val rows = OuraMappers.toWorkouts(workouts)
    val samples =
      if (rows.isEmpty()) emptyList()
      else
        attempt("Syke") {
          client.heartRate(
            Instant.ofEpochMilli(rows.minOf { it.startTimeUtc }),
            Instant.ofEpochMilli(rows.maxOf { it.endTimeUtc }),
          )
        }

    return OuraDiagnostics(
      fromDate = from.toString(),
      toDate = to.toString(),
      readinessDays = readiness.size,
      sleepDays = sleep.size,
      activityDays = activity.size,
      workouts = workouts.map { it.describe() },
      heartRateSamples = samples.size,
      failures = failures,
    )
  }

  /**
   * The heart-rate samples covering the workouts just fetched, or none.
   *
   * One request spanning all of them rather than one per workout: the series is asked for by
   * instant, so the window from the earliest start to the latest end covers every workout in the
   * sync at the cost of a single call.
   *
   * **A failure here is not a failure of the sync.** The commonest one is a `401` from a connection
   * granted before the `heartrate` scope existed, and letting that discard the readiness and sleep
   * scores that arrived successfully would trade the whole feature for the newest part of it. The
   * workouts are simply stored without a heart rate.
   */
  private suspend fun withHeartRatePerWorkout(
    rows: List<OuraWorkoutEntity>
  ): List<OuraWorkoutEntity> {
    if (rows.isEmpty()) return rows
    // One request per workout rather than one spanning all of them. A single span covering a
    // fortnight would download every night in between to find the twenty samples that belong to a
    // run — the sync window is now long enough that the difference is thousands of samples.
    var giveUp = false
    return rows.map { row ->
      if (giveUp) return@map row
      val samples =
        try {
          client.heartRate(
            Instant.ofEpochMilli(row.startTimeUtc),
            Instant.ofEpochMilli(row.endTimeUtc),
          )
        } catch (e: OuraException) {
          // A missing `heartrate` scope fails identically for every workout, so asking the other
          // twenty-seven times would spend requests to be told the same thing.
          giveUp = true
          emptyList()
        }
      OuraMappers.withHeartRate(listOf(row), samples).single()
    }
  }

  /**
   * Ties completed workouts to the sessions they answer, and stores the result.
   *
   * Separate from [sync] because it is a training decision rather than a fetch: it reads what is in
   * the database, decides, and writes only the link. Run after a sync, and safe to run again — an
   * unchanged decision rewrites the same value.
   */
  suspend fun matchWorkouts(sessions: List<PlannedSession>, fromUtc: Long, toUtc: Long) {
    val workouts = dao.getWorkoutsBetween(fromUtc, toUtc)
    if (workouts.isEmpty()) return
    val matches =
      matcher.execute(
        workouts = workouts.map { CompletedWorkout(it.id, it.startTimeUtc, it.endTimeUtc) },
        sessions = sessions,
      )
    for (workout in workouts) {
      val sessionId = matches[workout.id]
      // Written even when it is null: a session moved out of a workout's reach must lose the
      // workout it used to claim, or the screen would keep showing a pairing that no longer holds.
      if (workout.matchedSessionId != sessionId) dao.setMatchedSession(workout.id, sessionId)
    }
  }

  /** The completed Oura workouts tied to one planned session. Usually none or one. */
  fun observeWorkoutsFor(sessionId: String): Flow<List<CompletedSessionMetrics>> =
    dao.observeWorkoutsForSession(sessionId).map { rows -> rows.map { it.toMetrics() } }

  /**
   * Every matched workout, keyed by the session it belongs to.
   *
   * One flow for the whole screen rather than one per card: a list of ten sessions would otherwise
   * open ten database observers to answer a question the database can answer once. Where a session
   * somehow has two, the longer one wins — that is the one that looks like the session rather than
   * like a walk to the gym.
   */
  fun observeMatchedMetrics(): Flow<Map<String, CompletedSessionMetrics>> =
    dao.observeMatchedWorkouts().map { rows ->
      rows
        .groupBy { it.matchedSessionId!! }
        .mapValues { (_, forSession) ->
          forSession.maxBy { it.endTimeUtc - it.startTimeUtc }.toMetrics()
        }
    }

  /**
   * What Oura recorded on one day that no planned session claims.
   *
   * The counterpart to [observeMatchedMetrics], and the reason it exists: without it, a workout the
   * matcher could not place simply vanishes, and "we never fetched it" and "we fetched it and said
   * nothing" look identical from the screen.
   */
  fun observeUnmatchedOn(date: LocalDate, zone: ZoneId): Flow<List<CompletedSessionMetrics>> {
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return dao.observeUnmatchedWorkouts(from, to).map { rows -> rows.map { it.toMetrics() } }
  }

  /**
   * One day as the screens see it.
   *
   * Emits `null` when Oura has never been asked about this day, which is different from a day it
   * answered about with no numbers in it — the first is "we have not looked", the second is "the
   * ring was not worn". The card says different things about them.
   */
  fun observeDay(date: LocalDate): Flow<DailyRecovery?> =
    dao.observeDailySummary(date.toString()).map { it?.toDomain() }
}

/**
 * One workout as a single readable line, for the diagnostics screen.
 *
 * Straight from the DTO rather than from a stored row, because the question it answers is what Oura
 * sent — a row would already have been through parsing and could hide the very thing being looked
 * for. `source` is included: `autodetected` and `manual` are the difference between Oura noticing a
 * session and someone entering it, and that is exactly the kind of thing worth seeing.
 */
private fun OuraWorkoutDto.describe(): String =
  buildString {
    append(day ?: "?")
    append("  ")
    append(activity ?: "?")
    append("  ")
    append(startDatetime?.substringAfter('T')?.take(5) ?: "?")
    calories?.let { append("  ${it.toInt()} kcal") }
    distance?.let { append("  ${"%.1f".format(it / 1000.0)} km") }
    source?.let { append("  [$it]") }
  }

/** Metres and a millisecond span are Oura's units; kilometres and minutes are the screen's. */
private fun OuraWorkoutEntity.toMetrics(): CompletedSessionMetrics =
  CompletedSessionMetrics(
    ouraWorkoutId = id,
    activityType = activityType,
    startTimeUtc = startTimeUtc,
    durationMin = ((endTimeUtc - startTimeUtc) / 60_000L).toInt().coerceAtLeast(0),
    calories = calories?.roundToInt(),
    distanceKm = distanceMeters?.let { it / 1000.0 },
    avgHeartRate = avgHeartRate,
    maxHeartRate = maxHeartRate,
  )

private fun OuraDailySummaryEntity.toDomain(): DailyRecovery =
  DailyRecovery(
    date = date,
    readiness = readinessScore,
    sleep = sleepScore,
    activity = activityScore,
    fetchedAtUtc = fetchedAtUtc,
  )
