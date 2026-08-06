package fi.merilainen.treenivalmentaja

import android.app.Application
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine


import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler

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


  val reminderScheduler: ReminderScheduler by lazy {
    ReminderScheduler(this)
  }

  val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase by lazy {
    RescheduleAlarmsUseCase(
      database = db,
      planDao = db.trainingPlanDao(),
      sessionDao = db.workoutSessionDao(),
      settingsStore = settingsStore,
      resolveReminderUseCase = resolveReminderUseCase,
      reminderScheduler = reminderScheduler
    )
  }

  override fun onCreate() {
    super.onCreate()
    NotificationChannels.createChannels(this)
  }
}
