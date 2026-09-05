package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stopwatch, as arithmetic rather than as a screen.
 *
 * Everything the header and the summary say about time comes through here, so the cases that
 * matter are the ones a real session produces and a demonstration never does: a movement skipped
 * after standing in front of it, a movement walked back to, and a rest that runs long because the
 * person is still fetching a kettlebell two screens later.
 */
class ActiveWorkoutTimingTest {

  // ------------------------------------------------------------------ accumulating

  @Test
  fun `net time is the movements and nothing else`() {
    val timing =
      ActiveWorkoutTiming()
        .plusMovement("1:1", 90)
        .plusBetween(30)
        .plusMovement("1:2", 45)
        .plusBetween(30)

    assertEquals(135, timing.netSeconds())
    assertEquals(60, timing.betweenSeconds)
  }

  /** Doing a movement, walking back and doing it again is time spent on that movement, twice. */
  @Test
  fun `a movement visited twice adds up`() {
    val timing = ActiveWorkoutTiming().plusMovement("1:1", 60).plusMovement("1:1", 25)

    assertEquals(85, timing.netSeconds())
    assertEquals(mapOf("1:1" to 85L), timing.movementSeconds)
  }

  /** The same movement in another round is a separate effort, not the same one again. */
  @Test
  fun `the same exercise in two rounds is counted separately`() {
    val timing = ActiveWorkoutTiming().plusMovement("1:1", 60).plusMovement("2:1", 75)

    assertEquals(135, timing.netSeconds())
    assertEquals(2, timing.movementSeconds.size)
  }

  /** A step that lasted less than a second banks nothing rather than a zero-valued entry. */
  @Test
  fun `nothing is recorded for no time at all`() {
    val timing = ActiveWorkoutTiming().plusMovement("1:1", 0).plusBetween(0)

    assertTrue(timing.movementSeconds.isEmpty())
    assertEquals(0, timing.betweenSeconds)
  }

  // ------------------------------------------------------------------ skipping

  /**
   * The case the read-time filter exists for. Standing in front of a movement for twenty seconds
   * and then declining it is not twenty seconds of training.
   */
  @Test
  fun `a skipped movement counts for neither the net time nor the summary`() {
    val timing = ActiveWorkoutTiming().plusMovement("1:1", 60).plusMovement("1:2", 20)

    assertEquals(60, timing.netSeconds(skippedKeys = listOf("1:2")))
    assertEquals(mapOf("1:1" to 60L), timing.performed(skippedKeys = listOf("1:2")))
  }

  /** Gross keeps what net gives up: the seconds are dropped from a total, not from the session. */
  @Test
  fun `skipping changes nothing about what was banked`() {
    val timing = ActiveWorkoutTiming().plusMovement("1:1", 60).plusMovement("1:2", 20)

    assertEquals(80, timing.netSeconds())
    assertEquals(2, timing.movementSeconds.size)
  }

  /** Skipped, walked back to, and done after all — the filter is not a deletion. */
  @Test
  fun `a movement skipped and then performed counts once it is no longer skipped`() {
    val timing = ActiveWorkoutTiming().plusMovement("1:1", 15).plusMovement("1:1", 45)

    assertEquals(0, timing.netSeconds(skippedKeys = listOf("1:1")))
    assertEquals(60, timing.netSeconds())
  }

  // ------------------------------------------------------------------ the gap's target

  private val steps =
    buildActiveWorkoutSteps(
      exercises =
        listOf(
          Exercise(name = "Goblet-kyykky", reps = 12, restSec = 30),
          Exercise(name = "Punnerrus", reps = 12, restSec = 45),
        ),
      rounds = 2,
      roundRestSec = 90,
    )

  @Test
  fun `a rest is measured against its own planned length`() {
    val restIndex = steps.indexOfFirst { it is ActiveWorkoutStep.Rest }

    assertEquals(30, steps.gapTargetSeconds(restIndex))
  }

  /**
   * The point of the whole rule. The rest hands over to the preparation screen at zero, so if the
   * gap were only the rest card it could barely be late at all — the preparation that follows is
   * where a person actually stands about.
   */
  @Test
  fun `the preparation after a rest is still inside that rest's gap`() {
    val restIndex = steps.indexOfFirst { it is ActiveWorkoutStep.Rest }

    assertEquals(30, steps.gapTargetSeconds(restIndex + 1))
    assertTrue(steps[restIndex + 1] is ActiveWorkoutStep.Prepare)
  }

  /** A round break is a planned gap like any other, and a longer one. */
  @Test
  fun `a round break carries its own target`() {
    val breakIndex = steps.indexOfFirst { it is ActiveWorkoutStep.RoundBreak }

    assertEquals(90, steps.gapTargetSeconds(breakIndex))
    assertEquals(90, steps.gapTargetSeconds(breakIndex + 1))
  }

  /** Nothing planned the walk to the mat before the first movement, so nothing can be late. */
  @Test
  fun `the first preparation of the session has no target`() {
    assertTrue(steps.first() is ActiveWorkoutStep.Prepare)
    assertNull(steps.gapTargetSeconds(0))
  }

  /** A movement is not a gap; it is never measured against a rest. */
  @Test
  fun `a movement has no gap target`() {
    val performIndex = steps.indexOfFirst { it is ActiveWorkoutStep.Perform }

    assertNull(steps.gapTargetSeconds(performIndex))
  }

  // ------------------------------------------------------------------ the warning

  @Test
  fun `a gap is late only once it passes its target`() {
    assertFalse(isGapOverrun(elapsedSeconds = 29, targetSeconds = 30))
    assertFalse(isGapOverrun(elapsedSeconds = 30, targetSeconds = 30))
    assertTrue(isGapOverrun(elapsedSeconds = 31, targetSeconds = 30))
  }

  /** No target, no verdict — the same discipline the rest of the app keeps about missing data. */
  @Test
  fun `an unplanned gap is never late`() {
    assertFalse(isGapOverrun(elapsedSeconds = 600, targetSeconds = null))
    assertFalse(isGapOverrun(elapsedSeconds = 600, targetSeconds = 0))
  }

  // ------------------------------------------------------------------ rests per movement

  /** A gap belongs to the movement it followed, the way `restSec` belongs to the exercise. */
  @Test
  fun `a gap is recorded against the movement before it`() {
    val restIndex = steps.indexOfFirst { it is ActiveWorkoutStep.Rest }

    assertEquals("1:1", steps.precedingMovementKey(restIndex))
    // And the preparation after it belongs to the same movement, because it is the same gap.
    assertEquals("1:1", steps.precedingMovementKey(restIndex + 1))
  }

  /** Nothing precedes the walk to the mat, so it is nobody's rest. */
  @Test
  fun `the first preparation follows no movement`() {
    assertNull(steps.precedingMovementKey(0))
  }

  /** A round break follows the last movement of the round it ends. */
  @Test
  fun `a round break belongs to the movement that closed the round`() {
    val breakIndex = steps.indexOfFirst { it is ActiveWorkoutStep.RoundBreak }

    assertEquals("1:2", steps.precedingMovementKey(breakIndex))
  }

  /** The rest card and the preparation after it add into one gap, not two. */
  @Test
  fun `the parts of one gap add together`() {
    val timing = ActiveWorkoutTiming().plusRest("1:1", 45).plusRest("1:1", 20)

    assertEquals(mapOf("1:1" to 65L), timing.restSeconds)
  }

  /** Gap seconds count twice on purpose: once as this movement's rest, once as session gap time. */
  @Test
  fun `a gap counts both against its movement and against the session`() {
    val timing = ActiveWorkoutTiming().plusBetween(45).plusRest("1:1", 45)

    assertEquals(45, timing.betweenSeconds)
    assertEquals(mapOf("1:1" to 45L), timing.restSeconds)
    assertEquals(0, timing.netSeconds())
  }

  /** A skipped movement's rest goes with it. */
  @Test
  fun `rests of skipped movements are left out`() {
    val timing = ActiveWorkoutTiming().plusRest("1:1", 40).plusRest("1:2", 55)

    assertEquals(mapOf("1:1" to 40L), timing.rests(skippedKeys = listOf("1:2")))
  }

  // ------------------------------------------------------------------ the summary

  @Test
  fun `the summary names the movements in the order they were done`() {
    val performed = mapOf("2:1" to 70L, "1:1" to 60L, "1:2" to 50L)

    assertEquals(
      listOf("Goblet-kyykky" to 60L, "Punnerrus" to 50L, "Goblet-kyykky" to 70L),
      steps.movementTimes(performed),
    )
  }

  /** A movement with no measurement is left out rather than shown as having taken no time. */
  @Test
  fun `unmeasured movements do not appear in the summary`() {
    assertEquals(listOf("Punnerrus" to 50L), steps.movementTimes(mapOf("1:2" to 50L)))
    assertTrue(steps.movementTimes(emptyMap()).isEmpty())
  }
}
