package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.repository.TransitionResult
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A read-only preview. Nothing moves until the user accepts this exact proposal. */
sealed interface MissedSessionsProposal {
  data object None : MissedSessionsProposal

  data class MoveOne(
    val sessionId: String,
    val fromDate: LocalDate,
    val toDate: LocalDate,
  ) : MissedSessionsProposal

  data class ShiftPlan(
    val missedSessionIds: List<String>,
    val firstMissedDate: LocalDate,
    val days: Long,
    val affectedSessions: Int,
  ) : MissedSessionsProposal
}

class TrainingEngine(
  private val repository: TrainingRepository,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase? = null
) {
  private val missedSessionsMutex = Mutex()

  /**
   * User triggers "Sick". All future sessions move to PAUSED_DUE_TO_ILLNESS.
   */
  suspend fun markSick(reason: String? = null) {
    val today = todayInPlanZone()
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
    val today = todayInPlanZone()
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
  suspend fun proposeMissedSessions(): MissedSessionsProposal {
    val today = todayInPlanZone()
    val sessions = repository.getSessions()

    // Find sessions in the past that are still PLANNED, NOTIFIED, REPLACED_WITH_LIGHTER_VERSION, etc.
    val missedSessions = sessions.filter {
      it.status.isOpen && LocalDate.parse(it.scheduledDate) < today
    }.sortedBy { LocalDate.parse(it.scheduledDate) }

    if (missedSessions.isEmpty()) return MissedSessionsProposal.None

    if (missedSessions.size == 1) {
      val sessionToMove = missedSessions.first()
      val nextRestDay = findNextRestDay(sessions, today)
      return MissedSessionsProposal.MoveOne(
        sessionId = sessionToMove.id,
        fromDate = LocalDate.parse(sessionToMove.scheduledDate),
        toDate = nextRestDay,
      )
    } else {
      val firstMissedDate = LocalDate.parse(missedSessions.first().scheduledDate)
      val daysToShift = today.toEpochDay() - firstMissedDate.toEpochDay()
      return MissedSessionsProposal.ShiftPlan(
        missedSessionIds = missedSessions.map { it.id },
        firstMissedDate = firstMissedDate,
        days = daysToShift,
        affectedSessions = sessions.count { it.status.isOpen },
      )
    }
  }

  /** Applies only a proposal that is still current; accepting twice can never move the plan twice. */
  suspend fun applyMissedSessions(proposal: MissedSessionsProposal): Boolean =
    missedSessionsMutex.withLock {
      if (proposal == MissedSessionsProposal.None || proposeMissedSessions() != proposal) {
        return@withLock false
      }
      val sessions = repository.getSessions()
      when (proposal) {
        is MissedSessionsProposal.MoveOne ->
          repository.reschedule(
            sessionId = proposal.sessionId,
            newDate = proposal.toDate,
            source = EventSource.ENGINE,
            note = "Siirretty käyttäjän hyväksymänä seuraavalle lepopäivälle",
          )
        is MissedSessionsProposal.ShiftPlan -> {
          val allOpenSessions = sessions.filter { it.status.isOpen }
          for (session in allOpenSessions) {
            val originalDate = LocalDate.parse(session.scheduledDate)
            repository.reschedule(
              sessionId = session.id,
              newDate = originalDate.plusDays(proposal.days),
              source = EventSource.ENGINE,
              note = "Koko suunnitelmaa siirretty käyttäjän hyväksymänä",
            )
          }
        }
        MissedSessionsProposal.None -> return@withLock false
      }
      rescheduleAlarmsUseCase?.execute()
      true
    }

  /**
   * Clears the backlog the other way: the missed sessions are marked done where they stand, and
   * nothing on the calendar moves.
   *
   * The shift proposal assumes the training still has to happen. That is the wrong assumption for a
   * backlog that accumulated while the app itself was being built — sessions nobody ever intended
   * to do — and with only "siirrä" and "hylkää" on offer there was no way to say so: rejecting is a
   * pure no-op, so the same 35 sessions were still missed tomorrow, and the card came back. This is
   * the third answer, and the only one that ends the question for good.
   *
   * It marks exactly the sessions named by [proposal] — the past-dated open ones — and never a
   * future session. Each transition is an ordinary user-sourced status change through the
   * repository, so every session gets its `SessionEvent` saying it was ticked off by hand rather
   * than recorded as it happened; the history stays honest about how these rows reached COMPLETED.
   *
   * A session paused by illness cannot go straight to COMPLETED (see [SessionStatus]), so it is
   * first returned to PLANNED — the transition table's own route back out of the pause — and
   * completed from there. Both writes are events, so that detour is visible too.
   *
   * @return how many sessions were marked done.
   */
  suspend fun completeMissedSessions(proposal: MissedSessionsProposal): Int =
    missedSessionsMutex.withLock {
      // The same staleness guard as applyMissedSessions: a preview the user is no longer looking
      // at must never decide what gets written.
      if (proposal == MissedSessionsProposal.None || proposeMissedSessions() != proposal) {
        return@withLock 0
      }
      val missedIds =
        when (proposal) {
          is MissedSessionsProposal.MoveOne -> listOf(proposal.sessionId)
          is MissedSessionsProposal.ShiftPlan -> proposal.missedSessionIds
          MissedSessionsProposal.None -> return@withLock 0
        }
      var completed = 0
      for (id in missedIds) {
        if (markDone(id)) completed++
      }
      // Reminders for sessions that are now closed have nothing left to remind about.
      rescheduleAlarmsUseCase?.execute()
      completed
    }

  /** One session to COMPLETED, via PLANNED when the transition table demands it. */
  private suspend fun markDone(sessionId: String): Boolean {
    val session = repository.getSession(sessionId) ?: return false
    if (!session.status.canTransitionTo(SessionStatus.COMPLETED)) {
      if (!session.status.canTransitionTo(SessionStatus.PLANNED)) return false
      repository.transition(
        sessionId = sessionId,
        target = SessionStatus.PLANNED,
        source = EventSource.USER,
        note = MARKED_DONE_NOTE,
      )
    }
    return repository.transition(
      sessionId = sessionId,
      target = SessionStatus.COMPLETED,
      source = EventSource.USER,
      note = MARKED_DONE_NOTE,
    ) == TransitionResult.Applied
  }

  /** Kept for explicit readiness actions; unlike the old launch path this is never automatic. */
  suspend fun handleMissedSessions() {
    applyMissedSessions(proposeMissedSessions())
  }

  private fun findNextRestDay(sessions: List<TrainingSession>, fromDate: LocalDate): LocalDate {
    val activeDates = sessions.filter { it.status.isOpen }.map { LocalDate.parse(it.scheduledDate) }.toSet()
    var checkDate = fromDate
    while (activeDates.contains(checkDate)) {
      checkDate = checkDate.plusDays(1)
    }
    return checkDate
  }

  private suspend fun todayInPlanZone(): LocalDate =
    LocalDate.now(clock.withZone(repository.activePlanTimeZone()))

  private companion object {
    /** Says in the event log what the status alone cannot: nobody trained, the row was ticked. */
    const val MARKED_DONE_NOTE = "Merkitty tehdyksi jälkikäteen"
  }
}
