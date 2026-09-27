package fi.merilainen.treenivalmentaja.data.automation

import android.content.Context
import androidx.work.*
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import java.util.concurrent.TimeUnit

class TrainingAutomationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val app = applicationContext as? TreenivalmentajaApplication ?: return Result.success()
    app.intervalsConnection.refreshState()
    app.analysisConnection.refreshState()
    return if (app.trainingAutomation.run()) Result.success() else Result.retry()
  }
  companion object {
    private const val PERIODIC = "training-automation"
    private const val ON_CHANGE = "training-automation-change"
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun schedule(context: Context, enabled: Boolean) = withManager(context) { manager ->
      if (enabled) manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<TrainingAutomationWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
      else { manager.cancelUniqueWork(PERIODIC); manager.cancelUniqueWork(ON_CHANGE) }
    }
    fun request(context: Context) = withManager(context) { manager ->
      manager.enqueueUniqueWork(ON_CHANGE, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<TrainingAutomationWorker>().setConstraints(constraints).build())
    }
    private fun withManager(context: Context, action: (WorkManager) -> Unit) {
      try { action(WorkManager.getInstance(context)) } catch (_: IllegalStateException) {
        // WorkManager may not be initialised in a test Application; foreground work remains valid.
      }
    }
  }
}
