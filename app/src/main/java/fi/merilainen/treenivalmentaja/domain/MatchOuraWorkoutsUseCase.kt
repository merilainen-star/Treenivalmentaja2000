package fi.merilainen.treenivalmentaja.domain

import kotlin.math.abs

/** One Oura workout, in the only terms matching needs. */
data class CompletedWorkout(
  val id: String,
  val startTimeUtc: Long,
  val endTimeUtc: Long,
  /** Oura's own word for it, e.g. `strengthTraining`, `walking`, `houseWork`. */
  val activityType: String,
)

/** One planned session, likewise. */
data class PlannedSession(
  val id: String,
  /** Epoch millis of the session's own scheduled moment. */
  val scheduledAtUtc: Long,
  val type: WorkoutType,
)

/**
 * Decides which planned session a completed Oura workout belongs to.
 *
 * **Same day, nearest in time, and the activity has to fit.** The activity check was deliberately
 * left out at first, on the grounds that Oura's `activity` is a free-form string nobody here had
 * seen real values of, and that a wrong pairing is at least visible where a missing one is not.
 * Two weeks of real data settled it: a fortnight held eleven `walking` entries against five
 * `strengthTraining` ones, so the nearest workout to a 09:00 strength session was almost always a
 * walk. A 1.8 km stroll was shown as that morning's strength training, and a day whose only Oura
 * entry was a walk claimed a session that never happened at all.
 *
 * So a workout now has to be the right *kind* of thing. Anything Oura calls something this app has
 * no mapping for — `houseWork`, and `walking` outside a walking session — matches nothing, and is
 * shown on its own rather than dropped.
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
        .filter { (workout, session, distance) ->
          distance <= MAX_DISTANCE_MILLIS && fits(workout.activityType, session.type)
        }
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

  /**
   * Whether Oura's word for an activity can be this app's kind of session.
   *
   * Compared with the punctuation and case stripped out, because Oura writes `strengthTraining`
   * while its own specification's prose and other endpoints use `strength_training`; both should
   * mean the same thing here rather than one of them silently meaning nothing.
   *
   * An unknown activity fits nothing. That is the deliberate direction to fail in: an unmatched
   * workout is listed under its own day, while a wrongly matched one becomes a lie on a session
   * card.
   */
  private fun fits(activity: String, type: WorkoutType): Boolean {
    val normalised = activity.lowercase().filter { it.isLetterOrDigit() }
    return normalised in when (type) {
      WorkoutType.STRENGTH -> STRENGTH_ACTIVITIES
      WorkoutType.RUNNING -> RUNNING_ACTIVITIES
      WorkoutType.SKIING -> SKIING_ACTIVITIES
    }
  }

  companion object {
    /**
     * Twelve hours: far enough that a session planned for the morning still claims the workout
     * actually done that evening, and near enough that it cannot reach into another day's.
     */
    const val MAX_DISTANCE_MILLIS = 12L * 60 * 60 * 1000

    /**
     * `strengthTraining` is the value observed in real Oura data; the rest are its plausible
     * siblings. The single-word forms (`run`, `nordicski`, …) are Strava's `SportType` values,
     * which flow through this same matcher — Strava's vocabulary is closed and documented where
     * Oura's is free-form, so these are from the specification rather than observation.
     */
    private val STRENGTH_ACTIVITIES =
      setOf("strengthtraining", "weighttraining", "crossfit", "resistancetraining")

    private val RUNNING_ACTIVITIES =
      setOf("running", "jogging", "trailrunning", "treadmill", "run", "trailrun", "virtualrun")

    private val SKIING_ACTIVITIES =
      setOf(
        "crosscountryskiing",
        "skiing",
        "nordicskiing",
        "alpineskiing",
        "backcountryskiing",
        "nordicski",
        "alpineski",
        "backcountryski",
        "rollerski",
      )
  }
}
