package fi.merilainen.treenivalmentaja.data.guide

/**
 * What a movement looks like and how it is performed, as some outside catalogue describes it.
 *
 * Deliberately carries no reps, load, sets or duration. Those belong to the training plan and to
 * nothing else — a catalogue does not know how many you are supposed to do today, and letting one
 * appear to say so would put two answers on the screen. See `docs/EXERCISE_GUIDE.md`.
 */
data class ExerciseGuide(
  val id: String,
  val name: String,
  val imageUrl: String,
  val instructions: List<String>,
  val targetMuscles: List<String>,
  val equipment: List<String>,
  /**
   * The credit line shown wherever this guide appears.
   *
   * Carried per guide rather than per provider because one of the providers requires it: wger's
   * images are individually licensed and name their own author, so two movements from the same
   * source can owe credit to different people.
   */
  val attribution: String,
)

/**
 * A source of [ExerciseGuide]s.
 *
 * The interface exists so the app is not welded to one catalogue: the terms of the current one
 * forbid storing what it returns, and if a permissively licensed dataset is adopted later only an
 * implementation of this changes. Nothing outside this package should name a provider.
 */
interface ExerciseGuideProvider {

  /** The value a plan writes in `guide.provider`. */
  val id: String

  /** The source's own credit line, which each [ExerciseGuide] builds its attribution from. */
  val attribution: String

  /**
   * Metadata for a reference the plan carried.
   *
   * @throws GuideNotFoundException when the provider has no such movement.
   * @throws GuideUnavailableException when the lookup could not be made.
   */
  suspend fun byId(id: String): ExerciseGuide

  /**
   * Best-effort lookup for an exercise that carries no reference.
   *
   * The result is a **suggestion**, never an answer, and an empty list is a perfectly ordinary
   * outcome — see `docs/EXERCISE_GUIDE.md` section 4.
   *
   * @throws GuideUnavailableException when the lookup could not be made.
   */
  suspend fun search(name: String): List<ExerciseGuide>
}

/**
 * Every provider a plan may name. Anything else is an import error.
 *
 * Two, because neither is enough alone. ExerciseDB has an animation for all 1500 of its movements
 * but is missing several bodyweight basics outright — there is no plank, side plank, plain squat,
 * bird dog or cat-cow in it. wger has all of those, but only a third of its 834 movements carry a
 * picture and those are stills. A plan pins each movement to whichever source actually has it.
 */
object GuideProviders {
  const val EXERCISEDB = "exercisedb"

  const val WGER = "wger"

  val known: Set<String> = setOf(EXERCISEDB, WGER)
}

/** The provider answered, and does not have this movement. Retrying will not change that. */
class GuideNotFoundException(message: String) : Exception(message)

/**
 * The lookup could not be made: no network, a rate limit, a 5xx, an unreadable body.
 *
 * [canRetry] is what separates "try again in a moment" from a dead end, and [message] is already
 * in Finnish because it is shown as written.
 */
class GuideUnavailableException(
  message: String,
  val canRetry: Boolean = true,
) : Exception(message)
