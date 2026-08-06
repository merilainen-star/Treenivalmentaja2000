package fi.merilainen.treenivalmentaja.domain

import androidx.room.withTransaction
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.dao.TrainingPlanDao
import fi.merilainen.treenivalmentaja.data.local.dao.WorkoutSessionDao
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import kotlinx.coroutines.flow.first

class RescheduleAlarmsUseCase(
  private val database: AppDatabase,
  private val planDao: TrainingPlanDao,
  private val sessionDao: WorkoutSessionDao,
  private val settingsStore: NotificationSettingsStore,
  private val resolveReminderUseCase: ResolveReminderUseCase
) {
  
  suspend fun execute() {
    val settings = settingsStore.settingsFlow.first()
    
    // We only update sessions that are PLANNED.
    // If they are NOTIFIED, STARTED, COMPLETED, SKIPPED, they are not rescheduled.
    val plannedSessions = sessionDao.getByStatus(SessionStatus.PLANNED)
    
    if (plannedSessions.isEmpty()) return
    
    val planIds = plannedSessions.map { it.planId }.distinct()
    val plans = planIds.mapNotNull { planDao.getById(it) }.associateBy { it.id }
    
    val updatedSessions = plannedSessions.mapNotNull { session ->
      val plan = plans[session.planId] ?: return@mapNotNull null
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
        session.copy(
          remindAtUtc = newRemindAtUtc,
          updatedAt = System.currentTimeMillis()
        )
      } else null
    }

    if (updatedSessions.isNotEmpty()) {
      database.withTransaction {
        for (session in updatedSessions) {
          sessionDao.update(session)
        }
      }
    }
  }
}
