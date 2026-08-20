package fi.merilainen.treenivalmentaja.data.oura

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import fi.merilainen.treenivalmentaja.data.repository.OuraSyncResult
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Fetches the last few days from Oura, in the background.
 *
 * WorkManager rather than the AlarmManager the reminders use, because the two jobs are not alike: a
 * reminder must fire at a named minute and needs nothing from the network, while this needs a
 * connection, may be deferred without anyone noticing, and must survive a reboot and retry with a
 * backoff. That is the job WorkManager exists for and the one AlarmManager is worst at.
 *
 * **[SYNC_DAYS] days rather than one.** Oura revises a day after the fact — a night is scored when
 * it has been processed, not at midnight — and a phone that was offline for a weekend would
 * otherwise have a permanent hole in it. Re-fetching a handful of days costs one request per
 * collection and repairs both.
 */
internal class OuraSyncWorker(
  context: Context,
  parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

  override suspend fun doWork(): Result {
    val app = applicationContext as? TreenivalmentajaApplication ?: return Result.success()
    val today = LocalDate.now(app.repository.activePlanTimeZone())
    return when (val outcome =
      app.ouraRepository.sync(from = today.minusDays(SYNC_DAYS - 1), to = today)) {
      is OuraSyncResult.Success -> Result.success()
      is OuraSyncResult.Failure ->
        // A rate limit or a dead network is worth coming back to; a rejected token or a request
        // Oura will refuse identically forever is not, and retrying it would spend battery to
        // reach the same answer.
        if (outcome.canRetry) Result.retry() else Result.success()
    }
  }

  companion object {
    private const val SYNC_DAYS = 14L

    private const val WORK_NAME = "oura-sync"

    /**
     * Once a day, on a network, and only one of it.
     *
     * `KEEP` rather than `UPDATE`: the schedule is re-requested on every launch, and replacing the
     * work each time would restart its period, so an app opened daily would sync on a timer that
     * never came round.
     */
    fun schedule(context: Context) {
      val request =
        PeriodicWorkRequestBuilder<OuraSyncWorker>(1, TimeUnit.DAYS)
          .setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
          )
          .build()
      withWorkManager(context) {
        it.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
      }
    }

    /** Stops the daily sync. Called when Oura is disconnected: there is nothing left to fetch. */
    fun cancel(context: Context) {
      withWorkManager(context) { it.cancelUniqueWork(WORK_NAME) }
    }

    /**
     * `WorkManager.getInstance` **throws** when WorkManager has not been initialised, and both
     * callers run from `Application.onCreate` where that is not guaranteed — under Robolectric it
     * is never initialised at all, and there the exception escaped into the application scope and
     * took the whole test JVM's coroutine machinery with it before any test had started.
     *
     * Swallowed rather than propagated because of what this schedules: a background refresh of data
     * the app also fetches when the screen opens. Losing it degrades freshness; letting it throw
     * would crash the app on launch over a convenience.
     */
    private inline fun withWorkManager(context: Context, block: (WorkManager) -> Unit) {
      try {
        block(WorkManager.getInstance(context))
      } catch (e: IllegalStateException) {
        // No WorkManager here. The foreground sync still runs.
      }
    }
  }
}
