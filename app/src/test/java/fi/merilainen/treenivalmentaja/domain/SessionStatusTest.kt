package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table in `docs/TRAINING_ENGINE.md` is normative. These tests are the executable
 * copy of it — if the table changes, both must change together.
 */
class SessionStatusTest {

  @Test
  fun `terminal statuses allow nothing`() {
    val terminal =
      listOf(
        SessionStatus.COMPLETED,
        SessionStatus.SKIPPED,
        SessionStatus.INTERRUPTED,
        SessionStatus.RESCHEDULED,
        SessionStatus.CANCELLED,
      )
    terminal.forEach { status ->
      assertTrue("$status should be terminal", status.isTerminal)
      assertFalse("$status should not be open", status.isOpen)
      assertEquals(emptySet<SessionStatus>(), status.allowedTransitions)
      SessionStatus.entries.forEach { target ->
        assertFalse("$status must not reach $target", status.canTransitionTo(target))
      }
    }
  }

  @Test
  fun `non-terminal statuses are open`() {
    val open =
      listOf(
        SessionStatus.PLANNED,
        SessionStatus.NOTIFIED,
        SessionStatus.STARTED,
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
        SessionStatus.PAUSED_DUE_TO_ILLNESS,
      )
    open.forEach { status ->
      assertTrue("$status should be open", status.isOpen)
      assertFalse("$status should not be terminal", status.isTerminal)
    }
  }

  @Test
  fun `planned reaches every other status`() {
    assertEquals(
      setOf(
        SessionStatus.NOTIFIED,
        SessionStatus.STARTED,
        SessionStatus.COMPLETED,
        SessionStatus.SKIPPED,
        SessionStatus.RESCHEDULED,
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
        SessionStatus.PAUSED_DUE_TO_ILLNESS,
        SessionStatus.CANCELLED,
      ),
      SessionStatus.PLANNED.allowedTransitions,
    )
  }

  @Test
  fun `notified cannot go back to planned`() {
    assertFalse(SessionStatus.NOTIFIED.canTransitionTo(SessionStatus.PLANNED))
  }

  @Test
  fun `started can only finish, be interrupted, or be cancelled`() {
    assertEquals(
      setOf(SessionStatus.COMPLETED, SessionStatus.INTERRUPTED, SessionStatus.CANCELLED),
      SessionStatus.STARTED.allowedTransitions,
    )
    assertFalse(SessionStatus.STARTED.canTransitionTo(SessionStatus.RESCHEDULED))
    assertFalse(
      SessionStatus.STARTED.canTransitionTo(SessionStatus.REPLACED_WITH_LIGHTER_VERSION)
    )
    // SKIPPED means "never started" now — a started session reports INTERRUPTED instead.
    assertFalse(SessionStatus.STARTED.canTransitionTo(SessionStatus.SKIPPED))
  }

  @Test
  fun `lighter version is not terminal and still has to be finished`() {
    assertFalse(SessionStatus.REPLACED_WITH_LIGHTER_VERSION.isTerminal)
    assertTrue(
      SessionStatus.REPLACED_WITH_LIGHTER_VERSION.canTransitionTo(SessionStatus.COMPLETED)
    )
    assertTrue(SessionStatus.REPLACED_WITH_LIGHTER_VERSION.canTransitionTo(SessionStatus.SKIPPED))
    assertTrue(
      SessionStatus.REPLACED_WITH_LIGHTER_VERSION.canTransitionTo(SessionStatus.RESCHEDULED)
    )
    // Lightening twice would silently halve the session again.
    assertFalse(
      SessionStatus.REPLACED_WITH_LIGHTER_VERSION.canTransitionTo(
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION
      )
    )
  }

  @Test
  fun `illness pause resumes to planned or is moved`() {
    assertEquals(
      setOf(SessionStatus.PLANNED, SessionStatus.RESCHEDULED, SessionStatus.CANCELLED),
      SessionStatus.PAUSED_DUE_TO_ILLNESS.allowedTransitions,
    )
    // A paused session must not be completable without first being resumed.
    assertFalse(SessionStatus.PAUSED_DUE_TO_ILLNESS.canTransitionTo(SessionStatus.COMPLETED))
  }

  @Test
  fun `no status transitions to itself`() {
    SessionStatus.entries.forEach { status ->
      assertFalse("$status must not transition to itself", status.canTransitionTo(status))
    }
  }
}
