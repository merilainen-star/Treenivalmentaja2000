package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuideProvider
import fi.merilainen.treenivalmentaja.data.guide.GuideNotFoundException
import fi.merilainen.treenivalmentaja.data.guide.GuideUnavailableException
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the exercise-guide sheet should show. Every one of these is a normal state, not an error
 * to be logged and forgotten — the session stays fully usable in all of them.
 */
sealed interface ExerciseGuideState {

  /** The movement as the *plan* named it. Always the sheet's title, whatever the source says. */
  val exerciseName: String

  data class Loading(override val exerciseName: String) : ExerciseGuideState

  /**
   * @param suggested true when this is a name-search hit the user picked rather than a reference
   *   the plan carried. The sheet has to keep saying so: the plan never claimed this is the same
   *   movement.
   */
  data class Loaded(
    override val exerciseName: String,
    val guide: ExerciseGuide,
    val suggested: Boolean = false,
  ) : ExerciseGuideState

  /** Name-search hits, offered as a question. Never adopted on the app's own initiative. */
  data class Suggestions(
    override val exerciseName: String,
    val matches: List<ExerciseGuide>,
  ) : ExerciseGuideState

  data class Unavailable(
    override val exerciseName: String,
    val message: String,
    val canRetry: Boolean,
  ) : ExerciseGuideState
}

/**
 * Resolves one exercise into something the sheet can draw, across every configured source.
 *
 * Two paths, and they do not meet. An exercise carrying `guide` is looked up through the provider
 * that reference names, and if that provider does not have it the answer is "not found" —
 * **never** a name search, and never another source. The plan named that movement deliberately,
 * and quietly showing a different one because the id was stale would be a lie the user has no way
 * to notice. An exercise without a reference is searched everywhere at once, and whatever comes
 * back is only ever a suggestion.
 *
 * The cache is a plain map in this object and dies with the process: ExerciseDB's terms forbid
 * storing what it returns anywhere that outlives "temporary operational needs". wger's licences
 * would permit more, but one cache serves both and the stricter rule wins — see
 * `docs/EXERCISE_GUIDE.md` section 3.
 */
class LoadExerciseGuideUseCase(private val providers: List<ExerciseGuideProvider>) {

  constructor(provider: ExerciseGuideProvider) : this(listOf(provider))

  private val mutex = Mutex()

  /** Access-ordered so the oldest untouched entry is the one evicted. */
  private val cache =
    object : LinkedHashMap<String, ExerciseGuideState>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExerciseGuideState>) =
        size > MAX_CACHED
    }

  suspend fun execute(exercise: Exercise): ExerciseGuideState {
    val key = cacheKey(exercise) ?: return unknownProvider(exercise)
    mutex.withLock { cache[key] }?.let { return it }

    val state =
      try {
        val reference = exercise.guide
        if (reference != null) {
          val provider =
            providers.firstOrNull { it.id == reference.provider }
              ?: return unknownProvider(exercise)
          ExerciseGuideState.Loaded(exercise.name, provider.byId(reference.id))
        } else {
          searchEverywhere(exercise.name)
        }
      } catch (e: GuideNotFoundException) {
        ExerciseGuideState.Unavailable(
          exerciseName = exercise.name,
          message = e.message ?: "Liikettä ei löytynyt lähteestä.",
          canRetry = false,
        )
      } catch (e: GuideUnavailableException) {
        // Deliberately not cached: a retry that returned the stored failure would be a button
        // that does nothing.
        return ExerciseGuideState.Unavailable(
          exerciseName = exercise.name,
          message = e.message ?: "Liiketietoja ei juuri nyt saatu.",
          canRetry = e.canRetry,
        )
      }

    mutex.withLock { cache[key] = state }
    return state
  }

  /**
   * Asks every source at once and pools what comes back.
   *
   * One source being down does not hide the other's answer: the search fails only when they all
   * fail, which is the difference between "the network is gone" and "that one service is having
   * a moment".
   */
  private suspend fun searchEverywhere(name: String): ExerciseGuideState = coroutineScope {
    val outcomes =
      providers
        .map { provider -> async { runCatching { provider.search(name) } } }
        .map { it.await() }

    val failure = outcomes.mapNotNull { it.exceptionOrNull() }.firstOrNull()
    if (outcomes.all { it.isFailure }) {
      throw failure ?: GuideUnavailableException("Liiketietoja ei juuri nyt saatu.")
    }

    val matches =
      outcomes
        .mapNotNull { it.getOrNull() }
        .flatten()
        .distinctBy { it.name.lowercase(Locale.ROOT) }
        .sortedBy { it.name.length }
        .take(MAX_SUGGESTIONS)

    when (matches.size) {
      0 ->
        ExerciseGuideState.Unavailable(
          exerciseName = name,
          message = "Ei osumaa. Lisää liikkeelle guide-viite suunnitelmaan.",
          canRetry = false,
        )
      // One candidate is shown outright rather than as a list of one — still labelled a
      // suggestion, because a single hit is no more certain than the top of five.
      1 -> ExerciseGuideState.Loaded(name, matches.first(), suggested = true)
      else -> ExerciseGuideState.Suggestions(name, matches)
    }
  }

  private fun unknownProvider(exercise: Exercise) =
    ExerciseGuideState.Unavailable(
      exerciseName = exercise.name,
      message = "Tuntematon liiketietolähde \"${exercise.guide?.provider}\".",
      canRetry = false,
    )

  /** `null` when the plan names a provider this build cannot read. */
  private fun cacheKey(exercise: Exercise): String? {
    val reference = exercise.guide ?: return "name:${exercise.name.lowercase(Locale.ROOT)}"
    if (providers.none { it.id == reference.provider }) return null
    return "id:${reference.provider}:${reference.id}"
  }

  private companion object {
    /** A session has a handful of movements; this holds several sessions' worth and no more. */
    const val MAX_CACHED = 64

    const val MAX_SUGGESTIONS = 5
  }
}
