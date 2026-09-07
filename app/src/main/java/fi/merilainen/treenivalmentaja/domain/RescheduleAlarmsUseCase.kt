package fi.merilainen.treenivalmentaja.domain

import androidx.room.withTransaction
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.dao.TrainingPlanDao
import fi.merilainen.treenivalmentaja.data.local.dao.WorkoutSessionDao
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler


/**
 * `open`, like [fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler], so a test can hand
 * something else a no-op. Rescheduling reads the notification settings out of DataStore, which
 * runs on its own dispatcher — a ViewModel test driving a virtual clock would otherwise assert on
 * state that had not been written yet, and pass or fail by timing.
 */
open class RescheduleAlarmsUseCase(
  private val database: AppDatabase,
  private val planDao: TrainingPlanDao,
  private val sessionDao: WorkoutSessionDao,

  private val settingsStore: NotificationSettingsStore,
  private val resolveReminderUseCase: ResolveReminderUseCase,
  private val reminderScheduler: ReminderScheduler
) {
  
  private val mutex = Mutex()

  open suspend fun execute() = mutex.withLock {
    val previousCount = settingsStore.alarmCountFlow.first()
    if (previousCount > 0) {
        val requestCodes = (0 until previousCount).toList()
        reminderScheduler.cancelAll(requestCodes)
    }

    val settings = settingsStore.settingsFlow.first()

    val currentSessions = database.withTransaction {
      // Preserve the active-plan guard even though imports now delete replaced plans.
      val plannedSessions = sessionDao.getByStatusInActivePlan(SessionStatus.PLANNED)
      val plans = plannedSessions.map { it.planId }.distinct()
        .mapNotNull { planDao.getById(it) }.associateBy { it.id }

      for (session in plannedSessions) {
        val plan = plans[session.planId] ?: continue
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
          sessionDao.updateReminder(session.id, newRemindAtUtc, System.currentTimeMillis())
        }
      }

      sessionDao.getByStatusInActivePlan(SessionStatus.PLANNED)
    }
    if (currentSessions.isEmpty()) {
      settingsStore.updateAlarmCount(0)
      return@withLock
    }
    val now = System.currentTimeMillis()
    val windowEnd = now + ReminderScheduler.REMINDER_WINDOW_DAYS * 24L * 60 * 60 * 1000
    
    val sessionsToSchedule = currentSessions
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
