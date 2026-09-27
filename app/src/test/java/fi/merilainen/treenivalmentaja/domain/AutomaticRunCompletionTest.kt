package fi.merilainen.treenivalmentaja.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticRunCompletionTest {
  private val zone = ZoneId.of("Europe/Helsinki")
  private val start = Instant.parse("2026-09-26T21:15:00Z").toEpochMilli()
  private val session = TrainingSession("run", "plan", WorkoutType.RUNNING, 1, "2026-09-27", "00:15", start, durationMin = 30)
  private val run = RecordedRun("watch", start, 1800, 5000.0, "SUUNTO", "run")
  private fun match(sessions: List<TrainingSession> = listOf(session), runs: List<RecordedRun> = listOf(run), now: Long = start + 3_600_000) =
    AutomaticRunCompletion.candidates(sessions, runs, zone, now)
  @Test fun `uses plan local day across midnight UTC`() { assertEquals(1, match().size) }
  @Test fun `distance only easy runs complete while short recordings and absent targets do not`() {
    val distanceOnly = session.copy(durationMin = null, distanceKm = 7.0)
    assertEquals(1, match(listOf(distanceOnly)).size)
    assertTrue(match(listOf(distanceOnly), listOf(run.copy(distanceMeters = 1000.0))).isEmpty())
    assertTrue(match(listOf(session.copy(durationMin = null, distanceKm = null))).isEmpty())
    assertTrue(match(listOf(session.copy(distanceKm = 12.0))).isEmpty())
  }
  @Test fun `two sessions or two recordings need manual confirmation`() {
    assertTrue(match(listOf(session, session.copy(id = "other"))).isEmpty())
    assertTrue(match(runs = listOf(run, run.copy(activityId = "other"))).isEmpty())
  }
  @Test fun `short incomplete manual unknown and unfinished recordings never auto complete`() {
    listOf(run.copy(durationSec = 100), run.copy(distanceMeters = null), run.copy(source = "MANUAL"),
      run.copy(source = null), run.copy(matchedSessionId = "other")).forEach { assertTrue(match(runs = listOf(it)).isEmpty()) }
    assertTrue(match(now = start + 1800_000).isEmpty())
  }
  @Test fun `user decisions and illness are respected`() {
    listOf(SessionStatus.COMPLETED, SessionStatus.SKIPPED, SessionStatus.INTERRUPTED, SessionStatus.PAUSED_DUE_TO_ILLNESS)
      .forEach { assertTrue(match(listOf(session.copy(status = it))).isEmpty()) }
  }
}
