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

  @Test
  fun `the movement being performed is never listed as one of the upcoming ones`() {
    // Two movements, three rounds: round two begins with the movement round one begins with, so a
    // horizon that crosses the round boundary lists the current movement as "next".
    val steps =
      buildActiveWorkoutSteps(
        session(
          rounds = 3,
          exercises = listOf(Exercise("Kahvakuulakyykky", reps = 10), Exercise("Askelkyykky", reps = 8)),
        )
      )
    val firstPerform = steps.indexOfFirst { it is ActiveWorkoutStep.Perform }
    val current = (steps[firstPerform] as ActiveWorkoutStep.Perform).exercise.name

    val upcoming = upcomingInRound(steps, firstPerform)

    assertEquals(listOf("Askelkyykky"), upcoming)
    assertTrue(current !in upcoming)
  }

  @Test
  fun `the last movement of a round has nothing upcoming`() {
    val steps =
      buildActiveWorkoutSteps(
        session(rounds = 2, exercises = listOf(Exercise("Kyykky", reps = 5), Exercise("Punnerrus", reps = 5)))
      )
    val performs = steps.mapIndexedNotNull { i, step -> i.takeIf { step is ActiveWorkoutStep.Perform } }

    assertTrue(upcomingInRound(steps, performs[1]).isEmpty())
  }

  @Test
  fun `going back never lands on a movement that was skipped`() {
    val steps =
      buildActiveWorkoutSteps(
        session(
          exercises =
            listOf(
              Exercise("Kyykky", reps = 5),
              Exercise("Lankku", durationSec = 15),
              Exercise("Punnerrus", reps = 5),
            )
        )
      )
    val performs = steps.mapIndexedNotNull { i, s -> i.takeIf { s is ActiveWorkoutStep.Perform } }
    // Kyykky skipped, then Lankku and Punnerrus done: standing on the last movement.
    val skipped = listOf((steps[performs[0]] as ActiveWorkoutStep.Perform).key())

    val back = steps.previousStep(performs[2], skipped)

    // Lands on Punnerrus's own preparation screen, and going back again reaches Lankku — never
    // Kyykky, and never Kyykky's preparation screen either.
    assertTrue(back < performs[2])
    assertTrue(steps.previousStep(back, skipped) < back)
    var i = performs[2]
    repeat(6) {
      i = steps.previousStep(i, skipped)
      assertTrue(!steps.belongsToSkipped(i, skipped))
    }
  }

  @Test
  fun `a resumed workout moves off a skipped movement rather than reopening it`() {
    val steps =
      buildActiveWorkoutSteps(
        session(exercises = listOf(Exercise("Kyykky", reps = 5), Exercise("Punnerrus", reps = 5)))
      )
    val performs = steps.mapIndexedNotNull { i, s -> i.takeIf { s is ActiveWorkoutStep.Perform } }
    val skipped = listOf((steps[performs[0]] as ActiveWorkoutStep.Perform).key())

    // Stored right on the skipped movement, and on its preparation screen.
    assertTrue(!steps.belongsToSkipped(steps.resumeIndex(performs[0], skipped), skipped))
    assertTrue(!steps.belongsToSkipped(steps.resumeIndex(performs[0] - 1, skipped), skipped))
  }

  @Test
  fun `an ordinary resume opens exactly where it was left`() {
    val steps =
      buildActiveWorkoutSteps(
        session(exercises = listOf(Exercise("Kyykky", reps = 5), Exercise("Punnerrus", reps = 5)))
      )

    assertEquals(3, steps.resumeIndex(3, emptyList()))
    assertEquals(0, steps.resumeIndex(0, emptyList()))
  }

  @Test
  fun `nothing behind the first step is reported as nowhere to go`() {
    val steps = buildActiveWorkoutSteps(session(exercises = listOf(Exercise("Kyykky", reps = 5))))

    assertEquals(0, steps.previousStep(0, emptyList()))
  }

  @Test
  fun `skipping a movement skips the rest that belonged to it`() {
    val steps =
      buildActiveWorkoutSteps(
        session(
          exercises =
            listOf(
              Exercise("Kyykky", reps = 5, restSec = 10),
              Exercise("Punnerrus", reps = 5),
            )
        )
      )
    val kyykky = steps.indexOfFirst { it is ActiveWorkoutStep.Perform }
    val skipped = listOf((steps[kyykky] as ActiveWorkoutStep.Perform).key())

    // Straight after Kyykky comes its rest; skipping Kyykky must land past it.
    assertTrue(steps[kyykky + 1] is ActiveWorkoutStep.Rest)
    val landed = steps.nextStep(kyykky, skipped)
    assertTrue(steps[landed] !is ActiveWorkoutStep.Rest)
    assertEquals("Punnerrus", (steps[landed] as ActiveWorkoutStep.Prepare).exercise.name)
  }

  @Test
  fun `a rest is only skipped when its own movement was`() {
    val steps =
      buildActiveWorkoutSteps(
        session(
          exercises =
            listOf(
              Exercise("Kyykky", reps = 5, restSec = 10),
              Exercise("Punnerrus", reps = 5),
            )
        )
      )
    val kyykky = steps.indexOfFirst { it is ActiveWorkoutStep.Perform }

    // Nothing skipped: doing Kyykky leads to its rest, exactly as before.
    assertTrue(steps[steps.nextStep(kyykky, emptyList())] is ActiveWorkoutStep.Rest)
  }

  @Test
  fun `the round break survives skipping the last movement of the round`() {
    val steps =
      buildActiveWorkoutSteps(
        session(
          rounds = 2,
          roundRestSec = 20,
          exercises = listOf(Exercise("Kyykky", reps = 5), Exercise("Punnerrus", reps = 5)),
        )
      )
    val performs = steps.mapIndexedNotNull { i, s -> i.takeIf { s is ActiveWorkoutStep.Perform } }
    val lastOfRound = performs[1]
    val skipped = listOf((steps[lastOfRound] as ActiveWorkoutStep.Perform).key())

    // The break belongs to the turn between rounds, not to the movement before it.
    assertTrue(steps[steps.nextStep(lastOfRound, skipped)] is ActiveWorkoutStep.RoundBreak)
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
