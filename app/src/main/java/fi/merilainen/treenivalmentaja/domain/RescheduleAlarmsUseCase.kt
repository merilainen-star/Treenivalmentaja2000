package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.local.dao.TrainingPlanDao
import fi.merilainen.treenivalmentaja.data.local.dao.WorkoutSessionDao
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import kotlinx.coroutines.flow.first

class RescheduleAlarmsUseCase(
  private val planDao: TrainingPlanDao,
  private val sessionDao: WorkoutSessionDao,
  private val settingsStore: NotificationSettingsStore,
  private val resolveReminderUseCase: ResolveReminderUseCase
) {
  
  suspend fun execute() {
    val settings = settingsStore.settingsFlow.first()
    
    // We only update sessions that are PLANNED.
    // If they are NOTIFIED, STARTED, COMPLETED, SKIPPED, they are not rescheduled.
    val plannedSessions = sessionDao.getByStatus(fi.merilainen.treenivalmentaja.domain.SessionStatus.PLANNED)
    
    for (session in plannedSessions) {
      val plan = planDao.getById(session.planId) ?: continue
      val newRemindAtUtc = resolveReminderUseCase.resolveRemindAtUtc(
        sessionScheduledDate = session.scheduledDate,
        sessionScheduledTime = session.scheduledTime,
        sessionTimeIsFixed = session.timeIsFixed,
        sessionReminderOverride = session.reminderOverride,
        sessionType = session.type,
        timeZone = plan.timeZone,
        settings = settings
      )
      
      if (session.remindAtUtc != newRemindAtUtc) {
        sessionDao.update(
          session.copy(
            remindAtUtc = newRemindAtUtc,
            updatedAt = System.currentTimeMillis()
          )
        )
      }
    }
  }
}
