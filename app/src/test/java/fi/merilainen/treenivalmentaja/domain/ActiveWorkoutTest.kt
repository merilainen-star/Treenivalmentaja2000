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
  fun `a skipped movement counts as neither done nor unreached`() {
    val steps =
      buildActiveWorkoutSteps(
        session(
          rounds = 2,
          exercises = listOf(Exercise("Punnerrus", reps = 10), Exercise("Soutu", reps = 8)),
        )
      )
    val performs = steps.filterIsInstance<ActiveWorkoutStep.Perform>()
    val skippedKeys = listOf(performs[1].key())

    // Standing on the last step: three of the four movements were trained, one was skipped.
    assertEquals(3, steps.completedMovements(steps.lastIndex, skippedKeys))
    // Without the skip list the meter would have counted the movement it walked past.
    assertEquals(4, steps.completedMovements(steps.lastIndex))
    assertEquals(listOf(SkippedMovement(1, 2, "Soutu")), steps.skippedMovements(skippedKeys))
  }

  @Test
  fun `the same movement in a later round is a different skip`() {
    val steps =
      buildActiveWorkoutSteps(session(rounds = 2, exercises = listOf(Exercise("Kyykky", reps = 5))))
    val performs = steps.filterIsInstance<ActiveWorkoutStep.Perform>()

    assertEquals(listOf("1:1", "2:1"), performs.map { it.key() })
    assertEquals(
      listOf(SkippedMovement(2, 1, "Kyykky")),
      steps.skippedMovements(listOf(performs[1].key())),
    )
  }

  @Test
  fun `nothing is skipped and nothing is done before the first movement`() {
    val steps = buildActiveWorkoutSteps(session(exercises = listOf(Exercise("Punnerrus", reps = 10))))

    assertEquals(0, steps.completedMovements(0, emptyList()))
    assertTrue(steps.skippedMovements(emptyList()).isEmpty())
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
