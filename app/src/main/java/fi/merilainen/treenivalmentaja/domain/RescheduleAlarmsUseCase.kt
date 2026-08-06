package fi.merilainen.treenivalmentaja.domain

import androidx.room.withTransaction
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.dao.TrainingPlanDao
import fi.merilainen.treenivalmentaja.data.local.dao.WorkoutSessionDao
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import kotlinx.coroutines.flow.first

import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler


class RescheduleAlarmsUseCase(
  private val database: AppDatabase,
  private val planDao: TrainingPlanDao,
  private val sessionDao: WorkoutSessionDao,

  private val settingsStore: NotificationSettingsStore,
  private val resolveReminderUseCase: ResolveReminderUseCase,
  private val reminderScheduler: ReminderScheduler
) {
  
  suspend fun execute() {
    val previousCount = settingsStore.alarmCountFlow.first()
    if (previousCount > 0) {
        val requestCodes = (0 until previousCount).toList()
        reminderScheduler.cancelAll(requestCodes)
    }

    val settings = settingsStore.settingsFlow.first()

    val plannedSessions = sessionDao.getByStatus(SessionStatus.PLANNED)

    if (plannedSessions.isEmpty()) {
        settingsStore.updateAlarmCount(0)
        return
    }

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

    val now = System.currentTimeMillis()
    val windowEnd = now + ReminderScheduler.REMINDER_WINDOW_DAYS * 24L * 60 * 60 * 1000

    val allSessionsMap = plannedSessions.associateBy { it.id }.toMutableMap()
    updatedSessions.forEach { allSessionsMap[it.id] = it }
    
    val sessionsToSchedule = allSessionsMap.values
        .filter { it.remindAtUtc in now..windowEnd }
        .sortedBy { it.remindAtUtc }

    var count = 0
    sessionsToSchedule.forEach { session ->
        reminderScheduler.schedule(session.id, session.remindAtUtc, count)
        count++
    }

    reminderScheduler.schedule("REARM", windowEnd, count)
    count++

    settingsStore.updateAlarmCount(count)
  }
}
