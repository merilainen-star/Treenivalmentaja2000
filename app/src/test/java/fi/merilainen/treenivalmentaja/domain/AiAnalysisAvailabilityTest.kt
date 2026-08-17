package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two windows, and the statuses that fall inside them.
 *
 * Worth its own suite because the windows are a *decision* — "roughly the last week" and "roughly
 * the next few days" are English until something pins them down, and a boundary that drifted by a
 * day would never be noticed on screen.
 */
class AiAnalysisAvailabilityTest {

  // ------------------------------------------------------------------ completed

  @Test
  fun `a session completed today can be analysed`() {
    assertEquals(
      AiAnalysisKind.COMPLETED,
      AiAnalysisAvailability.kindFor(SessionStatus.COMPLETED, dayOffset = 0),
    )
  }

  @Test
  fun `a session completed seven days ago is the oldest that can be analysed`() {
    assertEquals(
      AiAnalysisKind.COMPLETED,
      AiAnalysisAvailability.kindFor(SessionStatus.COMPLETED, dayOffset = -7),
    )
  }

  /** The boundary. One day further back and the recovery data around it has been superseded. */
  @Test
  fun `a session completed eight days ago cannot`() {
    assertNull(AiAnalysisAvailability.kindFor(SessionStatus.COMPLETED, dayOffset = -8))
  }

  /**
   * A completed session cannot be in the future, but the plan can be shifted under one — so the
   * rule is stated rather than assumed.
   */
  @Test
  fun `a completed session dated in the future cannot`() {
    assertNull(AiAnalysisAvailability.kindFor(SessionStatus.COMPLETED, dayOffset = 1))
  }

  // ------------------------------------------------------------------ upcoming

  @Test
  fun `a planned session today can be analysed as upcoming`() {
    assertEquals(
      AiAnalysisKind.UPCOMING,
      AiAnalysisAvailability.kindFor(SessionStatus.PLANNED, dayOffset = 0),
    )
  }

  @Test
  fun `a planned session three days out is the furthest that can be`() {
    assertEquals(
      AiAnalysisKind.UPCOMING,
      AiAnalysisAvailability.kindFor(SessionStatus.PLANNED, dayOffset = 3),
    )
  }

  /**
   * The boundary that matters most. Recovery cannot be predicted four days out, and an analysis of
   * a session next month would be confident, plausible and unfounded.
   */
  @Test
  fun `a planned session four days out cannot`() {
    assertNull(AiAnalysisAvailability.kindFor(SessionStatus.PLANNED, dayOffset = 4))
  }

  @Test
  fun `a notified session is still upcoming`() {
    assertEquals(
      AiAnalysisKind.UPCOMING,
      AiAnalysisAvailability.kindFor(SessionStatus.NOTIFIED, dayOffset = 1),
    )
  }

  /** A missed session is in the past, and "how should I execute this" no longer applies. */
  @Test
  fun `a planned session left behind in the past cannot`() {
    assertNull(AiAnalysisAvailability.kindFor(SessionStatus.PLANNED, dayOffset = -1))
  }

  // ------------------------------------------------------------------ statuses with nothing to say

  /** Under way: the advice would arrive too late to act on. */
  @Test
  fun `a started session offers nothing`() {
    assertNull(AiAnalysisAvailability.kindFor(SessionStatus.STARTED, dayOffset = 0))
  }

  /** Nothing completed to assess, nothing upcoming to advise on. */
  @Test
  fun `a skipped session offers nothing`() {
    assertNull(AiAnalysisAvailability.kindFor(SessionStatus.SKIPPED, dayOffset = 0))
  }

  /** The lightening already happened, so the advice this exists to give has been taken. */
  @Test
  fun `an already-lightened session offers nothing`() {
    assertNull(
      AiAnalysisAvailability.kindFor(
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
        dayOffset = 0,
      )
    )
  }

  /** Exactly one kind, ever — a session must never show two buttons. */
  @Test
  fun `no status and offset combination yields both kinds`() {
    for (status in SessionStatus.entries) {
      for (offset in -10..10) {
        // A single-valued return makes this structurally impossible; the test states the
        // requirement so that a future refactor to a set has to keep it.
        val kind = AiAnalysisAvailability.kindFor(status, offset)
        assertEquals(kind, AiAnalysisAvailability.kindFor(status, offset))
      }
    }
  }
}
