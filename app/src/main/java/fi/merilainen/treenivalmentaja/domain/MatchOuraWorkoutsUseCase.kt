package fi.merilainen.treenivalmentaja.domain

import kotlin.math.abs

/** One Oura workout, in the only terms matching needs. */
data class CompletedWorkout(
  val id: String,
  val startTimeUtc: Long,
  val endTimeUtc: Long,
)

/** One planned session, likewise. */
data class PlannedSession(
  val id: String,
  /** Epoch millis of the session's own scheduled moment. */
  val scheduledAtUtc: Long,
)

/**
 * Decides which planned session a completed Oura workout belongs to.
 *
 * **Same day, nearest in time**, and nothing else. Deliberately not Oura's `activity` field: it is
 * a free-form string this app has never seen real values of, and a rule that silently drops a
 * workout because "strength_training" was not the word expected is worse than one that occasionally
 * pairs the wrong two things — a wrong pair is visible and can be corrected, a missing one looks
 * like Oura never recorded the session.
 *
 * The matching is one-to-one and greedy from the closest pair outwards: the tightest match in the
 * day is made first and both sides are then out of the running, so two sessions on one day get the
 * two workouts nearest each of them rather than both claiming the same one.
 *
 * A workout further than [MAX_DISTANCE_MILLIS] from any session is left unmatched. Without that, a
 * midnight walk would attach itself to a morning strength session for lack of competition.
 */
class MatchOuraWorkoutsUseCase {

  /** @return workout id -> session id, containing only the pairs it is confident about. */
  fun execute(
    workouts: List<CompletedWorkout>,
    sessions: List<PlannedSession>,
  ): Map<String, String> {
    if (workouts.isEmpty() || sessions.isEmpty()) return emptyMap()

    val candidates =
      workouts
        .flatMap { workout ->
          sessions.map { session ->
            Triple(workout, session, abs(workout.startTimeUtc - session.scheduledAtUtc))
          }
        }
        .filter { (_, _, distance) -> distance <= MAX_DISTANCE_MILLIS }
        // Ties broken by id so the same input always produces the same pairing, rather than
        // whichever the list happened to hold first.
        .sortedWith(compareBy({ it.third }, { it.first.id }, { it.second.id }))

    val matched = mutableMapOf<String, String>()
    val takenSessions = mutableSetOf<String>()
    for ((workout, session, _) in candidates) {
      if (workout.id in matched || session.id in takenSessions) continue
      matched[workout.id] = session.id
      takenSessions += session.id
    }
    return matched
  }

  companion object {
    /**
     * Twelve hours: far enough that a session planned for the morning still claims the workout
     * actually done that evening, and near enough that it cannot reach into another day's.
     */
    const val MAX_DISTANCE_MILLIS = 12L * 60 * 60 * 1000
  }
}
