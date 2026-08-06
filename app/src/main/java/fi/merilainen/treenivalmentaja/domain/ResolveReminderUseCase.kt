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

    val resolvedZonedDateTime: ZonedDateTime = when {
      sessionReminderOverride != null -> {
        val overrideTime = LocalTime.parse(sessionReminderOverride)
        ZonedDateTime.of(date, overrideTime, zone)
      }
      sessionTimeIsFixed && sessionScheduledTime != null -> {
        val scheduledTime = LocalTime.parse(sessionScheduledTime)
        ZonedDateTime.of(date, scheduledTime, zone).minusMinutes(settings.reminderOffsetMin.toLong())
      }
      else -> {
        val defaultStr = settings.getTimeForType(sessionType)
        val defaultTime = try {
          LocalTime.parse(defaultStr)
        } catch (e: Exception) {
          LocalTime.of(18, 0)
        }
        ZonedDateTime.of(date, defaultTime, zone)
      }
    }
    
    return resolvedZonedDateTime.toInstant().toEpochMilli()
  }
}
