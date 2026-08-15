package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app may ask about a poor readiness reading, and — more importantly — when it may not.
 *
 * Most of these are negative cases on purpose. The readiness indicator this replaced was removed
 * for showing the same verdict every day with nothing behind it, so the failure mode worth testing
 * hardest is a card appearing on a morning that does not warrant one.
 */
class ReadinessAdviceUseCaseTest {

  private val useCase = ReadinessAdviceUseCase()

  private val today = LocalDate.of(2026, 8, 15)
  private val yesterday = today.minusDays(1)

  // ------------------------------------------------------------------ the rule fires

  /** The case this was built for: the plan slipped, and the readiness number says why. */
  @Test
  fun `a missed session after a poor day is worth asking about`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = 57)),
        sessions =
          listOf(
            session("eilinen", yesterday, SessionStatus.NOTIFIED),
            session("tanaan", today, SessionStatus.PLANNED),
          ),
      )

    val offer = advice as ReadinessAdvice.Offer
    assertEquals(ReadinessAdvice.Concern.MISSED_AFTER_POOR_DAY, offer.concern)
    assertEquals(57, offer.readiness)
    assertEquals(listOf("eilinen"), offer.shiftableSessionIds)
    assertEquals(listOf("tanaan"), offer.lightenableSessionIds)
  }

  /**
   * A poor morning with a session ahead of it offers only lightening. Moving the whole programme
   * on the strength of one morning's number would be a bigger claim than the measurement supports.
   */
  @Test
  fun `a poor reading today offers lightening but not shifting`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(today to recovery(today, readiness = 62)),
        sessions = listOf(session("tanaan", today, SessionStatus.PLANNED)),
      )

    val offer = advice as ReadinessAdvice.Offer
    assertEquals(ReadinessAdvice.Concern.POOR_TODAY, offer.concern)
    assertTrue(offer.shiftableSessionIds.isEmpty())
    assertEquals(listOf("tanaan"), offer.lightenableSessionIds)
  }

  /** Both rules could fire; the missed session is the more useful thing to talk about. */
  @Test
  fun `the missed-session rule wins when both would fire`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay =
          mapOf(yesterday to recovery(yesterday, 57), today to recovery(today, 61)),
        sessions =
          listOf(
            session("eilinen", yesterday, SessionStatus.PLANNED),
            session("tanaan", today, SessionStatus.PLANNED),
          ),
      )

    assertEquals(
      ReadinessAdvice.Concern.MISSED_AFTER_POOR_DAY,
      (advice as ReadinessAdvice.Offer).concern,
    )
  }

  // ------------------------------------------------------------------ the rule stays quiet

  /**
   * The one that matters most. A day the ring was not worn has no readiness at all, and treating
   * that as a low score is exactly the "missing is not zero" mistake the Oura layer exists to
   * avoid.
   */
  @Test
  fun `a day with no reading produces no advice`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = null)),
        sessions = listOf(session("eilinen", yesterday, SessionStatus.PLANNED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  @Test
  fun `no Oura data at all produces no advice`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = emptyMap(),
        sessions = listOf(session("eilinen", yesterday, SessionStatus.PLANNED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  @Test
  fun `a good reading says nothing, however the week went`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = 88)),
        sessions = listOf(session("eilinen", yesterday, SessionStatus.PLANNED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /** 70 is where Oura's own wording stops saying "good"; at the boundary there is nothing to say. */
  @Test
  fun `the threshold is below seventy, not at it`() {
    val sessions = listOf(session("tanaan", today, SessionStatus.PLANNED))

    assertEquals(
      ReadinessAdvice.None,
      useCase.execute(today, mapOf(today to recovery(today, 70)), sessions),
    )
    assertTrue(
      useCase.execute(today, mapOf(today to recovery(today, 69)), sessions)
        is ReadinessAdvice.Offer
    )
  }

  /** Yesterday's session was done. A poor night after a completed session is not a problem. */
  @Test
  fun `a completed session yesterday is not a missed one`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = 50)),
        sessions = listOf(session("eilinen", yesterday, SessionStatus.COMPLETED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /** A session deliberately skipped is closed too — the person already decided. */
  @Test
  fun `a skipped session yesterday is not a missed one`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = 50)),
        sessions = listOf(session("eilinen", yesterday, SessionStatus.SKIPPED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /** Nothing was planned yesterday, so nothing was missed. A rest day is not a failure. */
  @Test
  fun `a poor day with no session planned says nothing`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = 45)),
        sessions = emptyList(),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /** A poor morning on a rest day has nothing to offer, so it says nothing rather than an empty card. */
  @Test
  fun `a poor reading today on a rest day produces no advice`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(today to recovery(today, readiness = 55)),
        sessions = listOf(session("huomenna", today.plusDays(1), SessionStatus.PLANNED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /** Offering to cut the same session twice would be a button that lies about what it does. */
  @Test
  fun `an already-lightened session is not offered for lightening again`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(today to recovery(today, readiness = 55)),
        sessions =
          listOf(
            session(
              "tanaan",
              today,
              SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
              appliedLighterVariant = true,
            )
          ),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /** A session already finished this morning cannot be started lighter. */
  @Test
  fun `a completed session today is not offered for lightening`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(today to recovery(today, readiness = 55)),
        sessions = listOf(session("tanaan", today, SessionStatus.COMPLETED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  /**
   * Yesterday's session was missed and today is a rest day: there is still something to shift,
   * so the question is worth asking with only that half of it.
   */
  @Test
  fun `a missed session with nothing to lighten still offers a shift`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(yesterday to recovery(yesterday, readiness = 57)),
        sessions = listOf(session("eilinen", yesterday, SessionStatus.PLANNED)),
      )

    val offer = advice as ReadinessAdvice.Offer
    assertEquals(listOf("eilinen"), offer.shiftableSessionIds)
    assertTrue(offer.lightenableSessionIds.isEmpty())
  }

  /** Two days ago is not yesterday. The rule reads one day back, deliberately. */
  @Test
  fun `a poor day further back is not this morning's question`() {
    val advice =
      useCase.execute(
        today = today,
        recoveryByDay = mapOf(today.minusDays(2) to recovery(today.minusDays(2), 40)),
        sessions = listOf(session("toissapaivana", today.minusDays(2), SessionStatus.PLANNED)),
      )

    assertEquals(ReadinessAdvice.None, advice)
  }

  // ------------------------------------------------------------------ helpers

  private fun recovery(date: LocalDate, readiness: Int?) =
    DailyRecovery(date = date.toString(), readiness = readiness)

  private fun session(
    id: String,
    date: LocalDate,
    status: SessionStatus,
    appliedLighterVariant: Boolean = false,
  ) =
    TrainingSession(
      id = id,
      planId = "plan",
      type = WorkoutType.STRENGTH,
      weekNumber = 1,
      scheduledDate = date.toString(),
      scheduledTime = "09:00",
      remindAtUtc = date.toEpochDay() * 86_400_000L,
      status = status,
      appliedLighterVariant = appliedLighterVariant,
    )
}
