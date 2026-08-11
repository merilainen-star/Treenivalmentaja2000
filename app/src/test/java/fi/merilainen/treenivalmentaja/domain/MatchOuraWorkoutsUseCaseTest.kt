package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.domain.WorkoutType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which completed workout belongs to which planned session.
 *
 * The rule is deliberately blunt — same day, nearest in time — and these are the cases that decide
 * whether blunt is good enough: two sessions in one day, a workout hours from anything, and the
 * pairing having to be stable rather than dependent on list order.
 */
class MatchOuraWorkoutsUseCaseTest {

  private val matcher = MatchOuraWorkoutsUseCase()

  private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

  private fun workout(
    id: String,
    start: String,
    durationMin: Long = 40,
    activity: String = "strengthTraining",
  ) =
    CompletedWorkout(
      id = id,
      startTimeUtc = at(start),
      endTimeUtc = at(start) + durationMin * 60_000,
      activityType = activity,
    )

  private fun session(id: String, at: String, type: WorkoutType = WorkoutType.STRENGTH) =
    PlannedSession(id = id, scheduledAtUtc = at(at), type = type)

  @Test
  fun `a workout near a session is matched to it`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("w1", "2026-08-10T15:05:00Z")),
        sessions = listOf(session("s1", "2026-08-10T15:00:00Z")),
      )

    assertEquals(mapOf("w1" to "s1"), matches)
  }

  /** The whole point of "nearest": the plan said morning, the workout happened in the evening. */
  @Test
  fun `a session done hours late still claims its workout`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("w1", "2026-08-10T17:00:00Z")),
        sessions = listOf(session("s1", "2026-08-10T06:00:00Z")),
      )

    assertEquals(mapOf("w1" to "s1"), matches)
  }

  /**
   * Two sessions and two workouts on one day. Each session must get the workout nearest *it*, not
   * both of them fighting over whichever comes first in the list.
   */
  @Test
  fun `two sessions in a day take the workout nearest each`() {
    val matches =
      matcher.execute(
        workouts =
          listOf(workout("evening", "2026-08-10T17:00:00Z"), workout("morning", "2026-08-10T07:00:00Z")),
        sessions = listOf(session("s-am", "2026-08-10T07:30:00Z"), session("s-pm", "2026-08-10T18:00:00Z")),
      )

    assertEquals(mapOf("morning" to "s-am", "evening" to "s-pm"), matches)
  }

  /** One session cannot be answered by two workouts; the closer one wins and the other is loose. */
  @Test
  fun `a session takes only one workout`() {
    val matches =
      matcher.execute(
        workouts =
          listOf(workout("far", "2026-08-10T09:00:00Z"), workout("near", "2026-08-10T07:10:00Z")),
        sessions = listOf(session("s1", "2026-08-10T07:00:00Z")),
      )

    assertEquals(mapOf("near" to "s1"), matches)
    assertNull(matches["far"])
  }

  /** Without a limit a midnight walk would attach itself to a morning session for lack of rivals. */
  @Test
  fun `a workout further away than the limit is left unmatched`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("w1", "2026-08-10T23:00:00Z")),
        sessions = listOf(session("s1", "2026-08-10T06:00:00Z")),
      )

    assertTrue(matches.toString(), matches.isEmpty())
  }

  @Test
  fun `a workout on another day is not matched`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("w1", "2026-08-12T07:00:00Z")),
        sessions = listOf(session("s1", "2026-08-10T07:00:00Z")),
      )

    assertTrue(matches.toString(), matches.isEmpty())
  }

  /** The same input must always pair the same way, whatever order the lists arrive in. */
  @Test
  fun `an exact tie is broken the same way every time`() {
    val workouts =
      listOf(workout("w1", "2026-08-10T07:00:00Z"), workout("w2", "2026-08-10T07:00:00Z"))
    val sessions = listOf(session("s1", "2026-08-10T07:00:00Z"), session("s2", "2026-08-10T07:00:00Z"))

    val first = matcher.execute(workouts, sessions)
    val second = matcher.execute(workouts.reversed(), sessions.reversed())

    assertEquals(first, second)
    assertEquals(2, first.size)
  }

  // ------------------------------------------------------------------ the activity has to fit

  /**
   * The failure this rule was added for, taken from real data. A fortnight held eleven `walking`
   * entries against five `strengthTraining` ones, so the workout nearest a 09:00 strength session
   * was almost always a walk — and a 1.8 km stroll was shown as that morning's strength training.
   */
  @Test
  fun `a walk does not become a strength session`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("walk", "2026-08-09T07:37:00Z", activity = "walking")),
        sessions = listOf(session("s1", "2026-08-09T06:00:00Z", WorkoutType.STRENGTH)),
      )

    assertTrue(matches.toString(), matches.isEmpty())
  }

  /** And the day whose only Oura entry is a walk leaves its strength session honestly empty. */
  @Test
  fun `a nearer walk does not beat a further strength workout`() {
    val matches =
      matcher.execute(
        workouts =
          listOf(
            workout("walk", "2026-08-08T07:41:00Z", activity = "walking"),
            workout("gym", "2026-08-08T12:13:00Z", activity = "strengthTraining"),
          ),
        sessions = listOf(session("s1", "2026-08-08T06:00:00Z", WorkoutType.STRENGTH)),
      )

    assertEquals(mapOf("gym" to "s1"), matches)
  }

  /** Oura writes `strengthTraining`; its own prose writes `strength_training`. Both are the same. */
  @Test
  fun `the activity is compared without case or punctuation`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("w1", "2026-08-09T07:00:00Z", activity = "strength_training")),
        sessions = listOf(session("s1", "2026-08-09T07:00:00Z", WorkoutType.STRENGTH)),
      )

    assertEquals(mapOf("w1" to "s1"), matches)
  }

  @Test
  fun `a run matches a running session and not a strength one`() {
    val run = listOf(workout("w1", "2026-08-09T07:00:00Z", activity = "running"))

    assertEquals(
      mapOf("w1" to "s1"),
      matcher.execute(run, listOf(session("s1", "2026-08-09T07:00:00Z", WorkoutType.RUNNING))),
    )
    assertTrue(
      matcher.execute(run, listOf(session("s2", "2026-08-09T07:00:00Z", WorkoutType.STRENGTH)))
        .isEmpty()
    )
  }

  /** `houseWork` is a real value Oura returned. It is not a training session of any kind. */
  @Test
  fun `an activity the app has no mapping for matches nothing`() {
    val matches =
      matcher.execute(
        workouts = listOf(workout("w1", "2026-08-09T07:00:00Z", activity = "houseWork")),
        sessions =
          listOf(
            session("s1", "2026-08-09T07:00:00Z", WorkoutType.STRENGTH),
            session("s2", "2026-08-09T07:00:00Z", WorkoutType.RUNNING),
          ),
      )

    assertTrue(matches.toString(), matches.isEmpty())
  }

  @Test
  fun `nothing to match is not an error`() {
    assertTrue(matcher.execute(emptyList(), listOf(session("s1", "2026-08-10T07:00:00Z"))).isEmpty())
    assertTrue(matcher.execute(listOf(workout("w1", "2026-08-10T07:00:00Z")), emptyList()).isEmpty())
  }
}
