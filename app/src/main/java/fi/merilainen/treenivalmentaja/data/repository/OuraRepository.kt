package fi.merilainen.treenivalmentaja.data.repository

import fi.merilainen.treenivalmentaja.data.local.dao.OuraDao
import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraException
import fi.merilainen.treenivalmentaja.data.oura.OuraMappers
import fi.merilainen.treenivalmentaja.domain.DailyRecovery
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
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
      val workoutRows = OuraMappers.toWorkouts(workouts)

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
   * One day as the screens see it.
   *
   * Emits `null` when Oura has never been asked about this day, which is different from a day it
   * answered about with no numbers in it — the first is "we have not looked", the second is "the
   * ring was not worn". The card says different things about them.
   */
  fun observeDay(date: LocalDate): Flow<DailyRecovery?> =
    dao.observeDailySummary(date.toString()).map { it?.toDomain() }
}

private fun OuraDailySummaryEntity.toDomain(): DailyRecovery =
  DailyRecovery(
    date = date,
    readiness = readinessScore,
    sleep = sleepScore,
    activity = activityScore,
    fetchedAtUtc = fetchedAtUtc,
  )
