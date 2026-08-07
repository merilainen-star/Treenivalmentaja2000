package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import java.time.Clock
import java.time.LocalDate

class TrainingEngine(
  private val repository: TrainingRepository,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase? = null
) {

  /**
   * User triggers "Sick". All future sessions move to PAUSED_DUE_TO_ILLNESS.
   */
  suspend fun markSick(reason: String? = null) {
    val today = LocalDate.now(clock)
    val sessions = repository.getSessions()

    val futureOpenSessions = sessions.filter {
      it.status.isOpen && LocalDate.parse(it.scheduledDate) >= today
    }

    for (session in futureOpenSessions) {
      repository.transition(
        sessionId = session.id,
        target = SessionStatus.PAUSED_DUE_TO_ILLNESS,
        source = EventSource.USER,
        note = reason ?: "Sairastuminen merkitty"
      )
    }
    rescheduleAlarmsUseCase?.execute()
  }

  /**
   * When marked "Recovered", the engine implements a gradual return:
   * - Day 1: 20 min very light.
   * - Day 2: Rest.
   * - Day 3: 50% of normal volume.
   * - Resumes normal plan shifted to start after the recovery days.
   */
  suspend fun markRecovered() {
    val today = LocalDate.now(clock)
    val sessions = repository.getSessions()

    val pausedSessions = sessions.filter { it.status == SessionStatus.PAUSED_DUE_TO_ILLNESS }
      .sortedBy { LocalDate.parse(it.scheduledDate) }

    if (pausedSessions.isEmpty()) return

    // We want to map:
    // - The 1st paused session to Day 1 (today)
    // - The 2nd paused session to Day 3 (today + 2 days)
    // - The 3rd and subsequent paused sessions to start from Day 4 (today + 3 days)
    // maintaining their original spacing for the remaining sessions.

    if (pausedSessions.size >= 1) {
      repository.reschedule(
        sessionId = pausedSessions[0].id,
        newDate = today,
        source = EventSource.ENGINE,
        note = "Palautuminen: 1. päivä (kevennetty)"
      )
    }

    if (pausedSessions.size >= 2) {
      repository.reschedule(
        sessionId = pausedSessions[1].id,
        newDate = today.plusDays(2),
        source = EventSource.ENGINE,
        note = "Palautuminen: 3. päivä (kevennetty)"
      )
    }

    if (pausedSessions.size > 2) {
      val thirdSessionDate = LocalDate.parse(pausedSessions[2].scheduledDate)
      val resumeDate = today.plusDays(3)
      val daysToShift = resumeDate.toEpochDay() - thirdSessionDate.toEpochDay()

      for (i in 2 until pausedSessions.size) {
        val session = pausedSessions[i]
        val originalDate = LocalDate.parse(session.scheduledDate)
        val newDate = originalDate.plusDays(daysToShift)
        repository.reschedule(
          sessionId = session.id,
          newDate = newDate,
          source = EventSource.ENGINE,
          note = "Siirretty sairauden vuoksi"
        )
      }
    }

    // Apply lighter versions to the first two sessions
    val oldIdsToLighten = pausedSessions.take(2).map { it.id }.toSet()
    val updatedSessions = repository.getSessions()
    for (session in updatedSessions) {
      if (session.originalSessionId in oldIdsToLighten) {
        repository.applyLighterVersion(session.id, EventSource.ENGINE)
      }
    }
    rescheduleAlarmsUseCase?.execute()
  }

  /**
   * Missed Sessions Handling
   * - One missed session: Shifted to the next rest day.
   * - Two or three missed sessions: Shifts the entire plan forward.
   */
  suspend fun handleMissedSessions() {
    val today = LocalDate.now(clock)
    val sessions = repository.getSessions()

    // Find sessions in the past that are still PLANNED, NOTIFIED, REPLACED_WITH_LIGHTER_VERSION, etc.
    val missedSessions = sessions.filter {
      it.status.isOpen && LocalDate.parse(it.scheduledDate) < today
    }.sortedBy { LocalDate.parse(it.scheduledDate) }

    if (missedSessions.isEmpty()) return

    if (missedSessions.size == 1) {
      // Move to the next rest day
      val sessionToMove = missedSessions.first()
      val nextRestDay = findNextRestDay(sessions, today)
      repository.reschedule(
        sessionId = sessionToMove.id,
        newDate = nextRestDay,
        source = EventSource.ENGINE,
        note = "Siirretty automaattisesti seuraavalle lepopäivälle"
      )
    } else {
      // Shift the entire plan forward
      val firstMissedDate = LocalDate.parse(missedSessions.first().scheduledDate)
      val daysToShift = today.toEpochDay() - firstMissedDate.toEpochDay()

      // We need to shift ALL open sessions (missed and future)
      val allOpenSessions = sessions.filter { it.status.isOpen }
      for (session in allOpenSessions) {
        val originalDate = LocalDate.parse(session.scheduledDate)
        val newDate = originalDate.plusDays(daysToShift)
        repository.reschedule(
          sessionId = session.id,
          newDate = newDate,
          source = EventSource.ENGINE,
          note = "Koko suunnitelmaa siirretty väliin jääneiden treenien vuoksi"
        )
      }
    }

    rescheduleAlarmsUseCase?.execute()
  }

  private fun findNextRestDay(sessions: List<TrainingSession>, fromDate: LocalDate): LocalDate {
    val activeDates = sessions.filter { it.status.isOpen }.map { LocalDate.parse(it.scheduledDate) }.toSet()
    var checkDate = fromDate
    while (activeDates.contains(checkDate)) {
      checkDate = checkDate.plusDays(1)
    }
    return checkDate
  }
}
