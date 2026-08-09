package fi.merilainen.treenivalmentaja.domain

import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuideProvider
import fi.merilainen.treenivalmentaja.data.guide.GuideNotFoundException
import fi.merilainen.treenivalmentaja.data.guide.GuideProviders
import fi.merilainen.treenivalmentaja.data.guide.GuideUnavailableException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every state the guide sheet can be in, driven by a fake provider. */
class LoadExerciseGuideUseCaseTest {

  private fun guide(id: String, name: String, source: String = "ExerciseDB") =
    ExerciseGuide(
      id = id,
      name = name,
      imageUrl = "https://static.invalid/$id.gif",
      instructions = listOf("Lie flat on a bench."),
      targetMuscles = listOf("pectorals"),
      equipment = listOf("barbell"),
      attribution = "Liiketiedot: $source",
    )

  /** Counts calls, so "the cache did its job" can be asserted rather than assumed. */
  private class FakeProvider(
    override val id: String = GuideProviders.EXERCISEDB,
    private val onById: (String) -> ExerciseGuide = { throw GuideNotFoundException("ei löytynyt") },
    private val onSearch: (String) -> List<ExerciseGuide> = { emptyList() },
  ) : ExerciseGuideProvider {
    override val attribution = "Liiketiedot: $id"
    var byIdCalls = 0
    var searchCalls = 0

    override suspend fun byId(id: String): ExerciseGuide {
      byIdCalls++
      return onById(id)
    }

    override suspend fun search(name: String): List<ExerciseGuide> {
      searchCalls++
      return onSearch(name)
    }
  }

  private fun exercise(name: String, guideRef: GuideRef? = null) =
    Exercise(name = name, reps = 10, guide = guideRef)

  private val reference = GuideRef(provider = GuideProviders.EXERCISEDB, id = "EIeI8Vf")

  // ------------------------------------------------------------------ the reference path

  @Test
  fun `a reference is looked up by id`() = runTest {
    val provider = FakeProvider(onById = { guide(it, "barbell bench press") })

    val state =
      LoadExerciseGuideUseCase(provider).execute(exercise("Penkkipunnerrus", reference))

    val loaded = state as ExerciseGuideState.Loaded
    assertEquals("Penkkipunnerrus", loaded.exerciseName)
    assertEquals("barbell bench press", loaded.guide.name)
    assertFalse("a reference the plan wrote is not a suggestion", loaded.suggested)
    assertEquals(0, provider.searchCalls)
  }

  /**
   * The plan named this movement deliberately. Quietly showing a different one because the id was
   * stale would be a substitution the user has no way to notice.
   */
  @Test
  fun `a reference that is not found never falls back to a name search`() = runTest {
    val provider = FakeProvider(
      onById = { throw GuideNotFoundException("Liikettä ei löytynyt lähteestä.") },
      onSearch = { listOf(guide("x", "barbell bench press")) },
    )

    val state = LoadExerciseGuideUseCase(provider).execute(exercise("Penkkipunnerrus", reference))

    val unavailable = state as ExerciseGuideState.Unavailable
    assertEquals("Liikettä ei löytynyt lähteestä.", unavailable.message)
    assertFalse("there is nothing to retry", unavailable.canRetry)
    assertEquals(0, provider.searchCalls)
  }

  @Test
  fun `a provider this build cannot read is reported rather than searched`() = runTest {
    val provider = FakeProvider(onSearch = { listOf(guide("x", "whatever")) })

    val state =
      LoadExerciseGuideUseCase(provider)
        .execute(exercise("Kyykky", GuideRef(provider = "wger", id = "123")))

    val unavailable = state as ExerciseGuideState.Unavailable
    assertTrue(unavailable.message.contains("wger"))
    assertEquals(0, provider.searchCalls)
    assertEquals(0, provider.byIdCalls)
  }

  // ------------------------------------------------------------------ the name path

  @Test
  fun `several hits are offered as suggestions`() = runTest {
    val provider = FakeProvider(
      onSearch = { listOf(guide("a", "front plank with twist"), guide("b", "side plank")) }
    )

    val state = LoadExerciseGuideUseCase(provider).execute(exercise("Lankku"))

    val suggestions = state as ExerciseGuideState.Suggestions
    assertEquals(2, suggestions.matches.size)
    assertEquals("Lankku", suggestions.exerciseName)
  }

  /** A single hit is shown outright — and still labelled a suggestion, because it is one. */
  @Test
  fun `one hit is shown but stays marked as a suggestion`() = runTest {
    val provider = FakeProvider(onSearch = { listOf(guide("a", "side plank")) })

    val state = LoadExerciseGuideUseCase(provider).execute(exercise("Lankku"))

    val loaded = state as ExerciseGuideState.Loaded
    assertEquals("side plank", loaded.guide.name)
    assertTrue(loaded.suggested)
  }

  @Test
  fun `no hits asks for a guide reference instead of guessing`() = runTest {
    val state = LoadExerciseGuideUseCase(FakeProvider()).execute(exercise("Kissa-lehmä"))

    val unavailable = state as ExerciseGuideState.Unavailable
    assertTrue(unavailable.message.contains("guide"))
    assertFalse(unavailable.canRetry)
  }

  // ------------------------------------------------------------------ failures

  @Test
  fun `an unavailable provider is retryable`() = runTest {
    val provider =
      FakeProvider(onById = { throw GuideUnavailableException("Liiketiedot vaativat verkkoyhteyden.") })

    val state = LoadExerciseGuideUseCase(provider).execute(exercise("Penkkipunnerrus", reference))

    val unavailable = state as ExerciseGuideState.Unavailable
    assertEquals("Liiketiedot vaativat verkkoyhteyden.", unavailable.message)
    assertTrue(unavailable.canRetry)
  }

  /** A retry that returned a stored failure would be a button that does nothing. */
  @Test
  fun `a failure is not cached`() = runTest {
    var attempt = 0
    val provider = FakeProvider(
      onById = {
        attempt++
        if (attempt == 1) throw GuideUnavailableException("Liiketiedot vaativat verkkoyhteyden.")
        guide(it, "barbell bench press")
      }
    )
    val useCase = LoadExerciseGuideUseCase(provider)
    val movement = exercise("Penkkipunnerrus", reference)

    assertTrue(useCase.execute(movement) is ExerciseGuideState.Unavailable)
    assertTrue(useCase.execute(movement) is ExerciseGuideState.Loaded)
    assertEquals(2, provider.byIdCalls)
  }

  // ------------------------------------------------------------------ the in-memory cache

  @Test
  fun `the same reference is fetched once per process`() = runTest {
    val provider = FakeProvider(onById = { guide(it, "barbell bench press") })
    val useCase = LoadExerciseGuideUseCase(provider)

    useCase.execute(exercise("Penkkipunnerrus", reference))
    useCase.execute(exercise("Penkkipunnerrus", reference))

    assertEquals(1, provider.byIdCalls)
  }

  /** Two movements with the same name are one lookup; the cache is keyed by the question asked. */
  @Test
  fun `a repeated name search is served from memory`() = runTest {
    val provider = FakeProvider(onSearch = { listOf(guide("a", "side plank"), guide("b", "front plank")) })
    val useCase = LoadExerciseGuideUseCase(provider)

    useCase.execute(exercise("Plank"))
    useCase.execute(exercise("plank"))

    assertEquals(1, provider.searchCalls)
  }

  // ------------------------------------------------------------------ two sources

  /**
   * Neither source has every movement — ExerciseDB has no plank at all — so the reference decides
   * which one is asked, and the other is not consulted.
   */
  @Test
  fun `a reference is resolved by the provider it names`() = runTest {
    val exercisedb = FakeProvider(GuideProviders.EXERCISEDB)
    val wger = FakeProvider(GuideProviders.WGER, onById = { guide(it, "Plank", "wger.de") })

    val state =
      LoadExerciseGuideUseCase(listOf(exercisedb, wger))
        .execute(exercise("Lankku", GuideRef(GuideProviders.WGER, "458")))

    val loaded = state as ExerciseGuideState.Loaded
    assertEquals("Plank", loaded.guide.name)
    assertEquals("Liiketiedot: wger.de", loaded.guide.attribution)
    assertEquals(1, wger.byIdCalls)
    assertEquals("the other source must not be consulted", 0, exercisedb.byIdCalls)
  }

  @Test
  fun `a name search asks every source and pools the hits`() = runTest {
    val exercisedb = FakeProvider(
      GuideProviders.EXERCISEDB,
      onSearch = { listOf(guide("a", "front plank with twist")) },
    )
    val wger = FakeProvider(
      GuideProviders.WGER,
      onSearch = { listOf(guide("458", "Plank", "wger.de")) },
    )

    val state = LoadExerciseGuideUseCase(listOf(exercisedb, wger)).execute(exercise("Plank"))

    val suggestions = state as ExerciseGuideState.Suggestions
    assertEquals(listOf("Plank", "front plank with twist"), suggestions.matches.map { it.name })
    assertEquals(1, exercisedb.searchCalls)
    assertEquals(1, wger.searchCalls)
  }

  /** One service having a moment must not hide what the other found. */
  @Test
  fun `a source that fails does not suppress the one that answered`() = runTest {
    val broken = FakeProvider(
      GuideProviders.EXERCISEDB,
      onSearch = { throw GuideUnavailableException("Liiketietolähde vastasi HTTP 503.") },
    )
    val working = FakeProvider(
      GuideProviders.WGER,
      onSearch = { listOf(guide("458", "Plank", "wger.de"), guide("580", "Side Plank", "wger.de")) },
    )

    val state = LoadExerciseGuideUseCase(listOf(broken, working)).execute(exercise("Plank"))

    assertEquals(2, (state as ExerciseGuideState.Suggestions).matches.size)
  }

  @Test
  fun `only when every source fails is the search a failure`() = runTest {
    val fail = { _: String -> throw GuideUnavailableException("Liiketiedot vaativat verkkoyhteyden.") }
    val state =
      LoadExerciseGuideUseCase(
        listOf(
          FakeProvider(GuideProviders.EXERCISEDB, onSearch = fail),
          FakeProvider(GuideProviders.WGER, onSearch = fail),
        )
      ).execute(exercise("Plank"))

    val unavailable = state as ExerciseGuideState.Unavailable
    assertTrue(unavailable.canRetry)
  }

  /** The same movement offered by both sources is one row, not two. */
  @Test
  fun `the same name from two sources is listed once`() = runTest {
    val a = FakeProvider(GuideProviders.EXERCISEDB, onSearch = { listOf(guide("x", "Plank")) })
    val b = FakeProvider(GuideProviders.WGER, onSearch = { listOf(guide("458", "plank", "wger.de")) })

    val state = LoadExerciseGuideUseCase(listOf(a, b)).execute(exercise("Plank"))

    assertTrue("a single hit is shown outright", state is ExerciseGuideState.Loaded)
  }

  @Test
  fun `different references are separate lookups`() = runTest {
    val provider = FakeProvider(onById = { guide(it, "movement $it") })
    val useCase = LoadExerciseGuideUseCase(provider)

    useCase.execute(exercise("A", reference))
    useCase.execute(exercise("B", GuideRef(GuideProviders.EXERCISEDB, "other")))

    assertEquals(2, provider.byIdCalls)
  }
}
