package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveReminderUseCaseTest {

  private val useCase = ResolveReminderUseCase()
  private val settings = NotificationSettings(
    runningTime = "16:00",
    strengthTime = "07:00",
    skiingTime = "10:00",
    reminderOffsetMin = 15
  )

  @Test
  fun `override wins over everything`() {
    val result = useCase.resolveRemindAtUtc(
      sessionScheduledDate = "2026-08-10",
      sessionScheduledTime = "12:00",
      sessionTimeIsFixed = true,
      sessionReminderOverride = "05:00",
      sessionType = WorkoutType.RUNNING,
      timeZone = "Europe/Helsinki",
      settings = settings
    )
    val expected = ZonedDateTime.of(LocalDate.parse("2026-08-10"), LocalTime.parse("05:00"), ZoneId.of("Europe/Helsinki")).toInstant().toEpochMilli()
    assertEquals(expected, result)
  }

  @Test
  fun `fixed time uses offset`() {
    val result = useCase.resolveRemindAtUtc(
      sessionScheduledDate = "2026-08-10",
      sessionScheduledTime = "12:00",
      sessionTimeIsFixed = true,
      sessionReminderOverride = null,
      sessionType = WorkoutType.RUNNING,
      timeZone = "Europe/Helsinki",
      settings = settings
    )
    // 12:00 minus 15 min = 11:45
    val expected = ZonedDateTime.of(LocalDate.parse("2026-08-10"), LocalTime.parse("11:45"), ZoneId.of("Europe/Helsinki")).toInstant().toEpochMilli()
    assertEquals(expected, result)
  }

  @Test
  fun `not fixed time uses settings default`() {
    val result = useCase.resolveRemindAtUtc(
      sessionScheduledDate = "2026-08-10",
      sessionScheduledTime = "12:00",
      sessionTimeIsFixed = false,
      sessionReminderOverride = null,
      sessionType = WorkoutType.STRENGTH,
      timeZone = "Europe/Helsinki",
      settings = settings
    )
    val expected = ZonedDateTime.of(LocalDate.parse("2026-08-10"), LocalTime.parse("07:00"), ZoneId.of("Europe/Helsinki")).toInstant().toEpochMilli()
    assertEquals(expected, result)
  }

  @Test
  fun `fixed time uses offset across midnight`() {
    val result = useCase.resolveRemindAtUtc(
      sessionScheduledDate = "2026-08-10",
      sessionScheduledTime = "00:10",
      sessionTimeIsFixed = true,
      sessionReminderOverride = null,
      sessionType = WorkoutType.RUNNING,
      timeZone = "Europe/Helsinki",
      settings = settings
    )
    // 00:10 on 10th minus 15 min = 23:55 on the 9th
    val expected = ZonedDateTime.of(LocalDate.parse("2026-08-09"), LocalTime.parse("23:55"), ZoneId.of("Europe/Helsinki")).toInstant().toEpochMilli()
    assertEquals(expected, result)
  }
}
