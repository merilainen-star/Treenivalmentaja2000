package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.*
import org.junit.Test

class TrialWorkoutTest {
  @Test fun `large imported round count does not expand unbounded trial data`() {
    val session = TrainingSession("s", "p", WorkoutType.STRENGTH, 1, "2026-09-13", null, 0,
      rounds = Int.MAX_VALUE, exercises = listOf(Exercise("Kyykky", reps = 10)))
    assertEquals("Liian monta kokeiluvaihetta", trialSteps(session).single().title)
  }

  @Test fun `fast timer stops at next phase without completing a distance phase`() {
    val steps = listOf(TrialStep("warmup", "", 15), TrialStep("run", "", meters = 400))
    val progress = TrialProgress(running = true).tick(steps, 30)
    assertEquals(1, progress.index)
    assertFalse(progress.running)
    assertEquals(progress, progress.tick(steps, 999))
    assertEquals(2, progress.next(steps).index)
  }

  @Test fun `paused timer and restart leave original prescription untouched`() {
    val session = TrainingSession("s", "p", WorkoutType.RUNNING, 1, "2026-09-13", null, 0,
      runSteps = listOf(RunStep("Run", durationSec = 60)))
    val steps = trialSteps(session)
    assertEquals(TrialProgress(), TrialProgress().tick(steps, 30))
    assertEquals(30, TrialProgress(running = true).tick(steps, 30).elapsed)
    assertEquals(SessionStatus.PLANNED, session.status)
    assertEquals(60, session.runSteps!!.single().durationSec)
  }
}
