package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWorkoutTest {
  @Test
  fun `preparation always precedes every movement and rests use authored values`() {
    val session =
      session(
        rounds = 2,
        roundRestSec = 45,
        exercises =
          listOf(
            Exercise("Kyykky", reps = 8, restSec = 30),
            Exercise("Lankku", durationSec = 20),
          ),
      )

    val steps = buildActiveWorkoutSteps(session)

    assertEquals(12, steps.size)
    assertTrue(steps[0] is ActiveWorkoutStep.Prepare)
    assertTrue(steps[1] is ActiveWorkoutStep.Perform)
    assertEquals(ActiveWorkoutStep.Rest(1, 30, "Lankku"), steps[2])
    assertTrue(steps[3] is ActiveWorkoutStep.Prepare)
    assertTrue(steps[4] is ActiveWorkoutStep.Perform)
    assertEquals(ActiveWorkoutStep.RoundBreak(45, 2), steps[5])
    assertTrue(steps.last() is ActiveWorkoutStep.Finish)
  }

  @Test
  fun `skip is recorded only on a perform step`() {
    val steps = buildActiveWorkoutSteps(session(exercises = listOf(Exercise("Punnerrus", reps = 10))))
    val prepare = ActiveWorkoutProgress()
    assertEquals(prepare, prepare.skip(steps))

    val skipped = prepare.advance(steps).skip(steps)
    assertEquals(2, skipped.stepIndex)
    assertEquals(listOf(SkippedMovement(1, 1, "Punnerrus")), skipped.skipped)
  }

  @Test
  fun `session without structured exercises cannot enter active mode`() {
    assertTrue(buildActiveWorkoutSteps(session(exercises = emptyList())).isEmpty())
  }

  private fun session(
    rounds: Int = 1,
    roundRestSec: Int? = null,
    exercises: List<Exercise>,
  ) =
    TrainingSession(
      id = "s1",
      planId = "p1",
      type = WorkoutType.STRENGTH,
      weekNumber = 1,
      scheduledDate = "2026-08-24",
      scheduledTime = "18:00",
      remindAtUtc = 0,
      rounds = rounds,
      roundRestSec = roundRestSec,
      exercises = exercises,
    )
}
