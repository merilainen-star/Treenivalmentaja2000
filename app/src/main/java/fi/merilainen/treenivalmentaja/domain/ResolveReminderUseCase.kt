package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ResolveReminderUseCase {
  fun resolveRemindAtUtc(
    sessionScheduledDate: String,
    sessionScheduledTime: String?,
    sessionTimeIsFixed: Boolean,
    sessionReminderOverride: String?,
    sessionType: WorkoutType,
    timeZone: String,
    settings: NotificationSettings
  ): Long {
    val date = LocalDate.parse(sessionScheduledDate)
    val zone = ZoneId.of(timeZone)

    val resolvedTime: LocalTime = when {
      sessionReminderOverride != null -> LocalTime.parse(sessionReminderOverride)
      sessionTimeIsFixed && sessionScheduledTime != null -> {
        LocalTime.parse(sessionScheduledTime).minusMinutes(settings.reminderOffsetMin.toLong())
      }
      else -> {
        val defaultStr = settings.getTimeForType(sessionType)
        try {
          LocalTime.parse(defaultStr)
        } catch (e: Exception) {
          LocalTime.of(18, 0)
        }
      }
    }
    
    return ZonedDateTime.of(date, resolvedTime, zone).toInstant().toEpochMilli()
  }
}
