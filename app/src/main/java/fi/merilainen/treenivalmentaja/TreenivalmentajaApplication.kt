package fi.merilainen.treenivalmentaja

import android.app.Application
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine

class TreenivalmentajaApplication : Application() {

  val db: AppDatabase by lazy { AppDatabase.getInstance(this) }

  val repository: TrainingRepository by lazy {
    TrainingRepository(db)
  }

  val engine: TrainingEngine by lazy {
    TrainingEngine(repository)
  }

  val settingsStore: NotificationSettingsStore by lazy {
    NotificationSettingsStore(this)
  }

  val resolveReminderUseCase: ResolveReminderUseCase by lazy {
    ResolveReminderUseCase()
  }

  val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase by lazy {
    RescheduleAlarmsUseCase(
      planDao = db.trainingPlanDao(),
      sessionDao = db.workoutSessionDao(),
      settingsStore = settingsStore,
      resolveReminderUseCase = resolveReminderUseCase
    )
  }
}
