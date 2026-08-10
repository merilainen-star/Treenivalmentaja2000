package fi.merilainen.treenivalmentaja

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuideProvider
import fi.merilainen.treenivalmentaja.data.guide.GuideProviders
import fi.merilainen.treenivalmentaja.data.guide.GuideUnavailableException
import fi.merilainen.treenivalmentaja.data.importer.PendingImport
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.oura.FakeOuraTokenStorage
import fi.merilainen.treenivalmentaja.data.oura.OuraAuthService
import fi.merilainen.treenivalmentaja.data.oura.OuraConnection
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.data.oura.OuraCredentials
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.data.update.UpdateInfo
import fi.merilainen.treenivalmentaja.data.update.UpdateService
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState
import fi.merilainen.treenivalmentaja.domain.GuideRef
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ViewModel's first tests.
 *
 * It had none, because nothing could be built without a database, an alarm scheduler and three use
 * cases. All of that is constructible under Robolectric — the only thing stubbed is the two places
 * that would reach outside the process: [ReminderScheduler], which is `open` precisely so a test
 * can hand it a no-op, and the guide provider.
 *
 * What is covered is the logic the ViewModel actually owns: the guide sheet's state machine, and
 * the import confirmation, which is the one place in the app where saying yes destroys data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: TrainingRepository

  /** Answers with whatever the test set, and counts how often it was asked. */
  private class FakeProvider : ExerciseGuideProvider {
    override val id = GuideProviders.EXERCISEDB
    override val attribution = "Liiketiedot: testi"
    var byId: (String) -> ExerciseGuide = { guide(it, "barbell bench press") }
    var calls = 0

    override suspend fun byId(id: String): ExerciseGuide {
      calls++
      return byId.invoke(id)
    }

    override suspend fun search(name: String) = emptyList<ExerciseGuide>()
  }

  private val provider = FakeProvider()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    // Room runs its queries and transactions on its own executors by default, which are outside
    // the test scheduler: `advanceUntilIdle()` would return while a write was still in flight and
    // the assertions would read the database halfway through. Handing Room the test dispatcher
    // puts every part of a launch on one clock.
    db =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AppDatabase::class.java,
        )
        .setQueryExecutor(dispatcher.asExecutor())
        .setTransactionExecutor(dispatcher.asExecutor())
        .allowMainThreadQueries()
        .build()
    repository = TrainingRepository(db)
  }

  @After
  fun tearDown() {
    db.close()
    Dispatchers.resetMain()
  }

  private fun viewModel(): WorkoutViewModel {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val settingsStore = NotificationSettingsStore(context)
    // Rescheduling reads DataStore, which lives outside the test scheduler; the alarm behaviour
    // has its own tests and is not what this file is about.
    val reschedule =
      object : RescheduleAlarmsUseCase(
        database = db,
        planDao = db.trainingPlanDao(),
        sessionDao = db.workoutSessionDao(),
        settingsStore = settingsStore,
        resolveReminderUseCase = ResolveReminderUseCase(),
        // Nothing here should reach AlarmManager; that is the alarm tests' job, not this one's.
        reminderScheduler = object : ReminderScheduler(context) {
          override fun schedule(sessionId: String, remindAtUtc: Long, requestCode: Int) = Unit
          override fun cancelAll(requestCodes: List<Int>) = Unit
        },
      ) {
        override suspend fun execute() = Unit
      }
    return WorkoutViewModel(
      repository = repository,
      engine = TrainingEngine(repository, rescheduleAlarmsUseCase = reschedule),
      settingsStore = settingsStore,
      rescheduleAlarmsUseCase = reschedule,
      checkForUpdateUseCase =
        CheckForUpdateUseCase(
          service = object : UpdateService {
            override suspend fun fetchLatest(): UpdateInfo = error("not asked")
          },
          // A version without a commit never consults the network at all.
          installedVersionName = "1.0",
        ),
      loadExerciseGuideUseCase = LoadExerciseGuideUseCase(provider),
      // A build with no `.env` — which is what a test run is — cannot connect Oura at all, so this
      // never reaches a Keystore or a network. The connection's own behaviour is covered by
      // OuraConnectionTest.
      ouraConnection =
        OuraConnection(
          store = FakeOuraTokenStorage(),
          authService = OuraAuthService({ PLACEHOLDER_CREDENTIALS }),
          credentials = { PLACEHOLDER_CREDENTIALS },
          onDisconnected = {},
        ),
    )
  }

  // ------------------------------------------------------------------ the guide sheet

  @Test
  fun `opening a guide shows what the provider returned`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()

    vm.openExerciseGuide(exercise())
    advanceUntilIdle()

    val loaded = vm.guideState.value as ExerciseGuideState.Loaded
    assertEquals("Penkkipunnerrus", loaded.exerciseName)
    assertEquals("barbell bench press", loaded.guide.name)
    assertFalse("a reference the plan wrote is not a suggestion", loaded.suggested)
  }

  @Test
  fun `the sheet says it is loading before the answer arrives`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()

    vm.openExerciseGuide(exercise())

    assertTrue(vm.guideState.value is ExerciseGuideState.Loading)
  }

  @Test
  fun `closing the guide clears it`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.openExerciseGuide(exercise())
    advanceUntilIdle()

    vm.closeExerciseGuide()

    assertNull(vm.guideState.value)
  }

  /** A failure is not cached, so the button has something to do. */
  @Test
  fun `retrying asks the provider again`() = runTest(dispatcher) {
    var attempt = 0
    provider.byId = {
      attempt++
      if (attempt == 1) throw GuideUnavailableException("Liiketiedot vaativat verkkoyhteyden.")
      guide(it, "barbell bench press")
    }
    val vm = viewModel()
    vm.openExerciseGuide(exercise())
    advanceUntilIdle()
    assertTrue(vm.guideState.value is ExerciseGuideState.Unavailable)

    vm.retryExerciseGuide()
    advanceUntilIdle()

    assertTrue(vm.guideState.value is ExerciseGuideState.Loaded)
    assertEquals(2, provider.calls)
  }

  @Test
  fun `picking a suggestion keeps it marked as one`() = runTest(dispatcher) {
    val vm = viewModel()
    vm.openExerciseGuide(exercise())
    advanceUntilIdle()

    vm.selectGuideSuggestion(guide("x", "side plank"))

    val loaded = vm.guideState.value as ExerciseGuideState.Loaded
    assertEquals("side plank", loaded.guide.name)
    assertTrue("picking one does not make it what the plan meant", loaded.suggested)
  }

  // ------------------------------------------------------------------ importing

  /**
   * The database is never empty in practice — a first launch seeds a starter week — so an import
   * always has something to overwrite, and must always ask.
   */
  @Test
  fun `an import that would replace the stored plan asks first`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()

    vm.importPlanJson(PLAN)
    advanceUntilIdle()

    val prompt = vm.pendingImport.value!!
    assertTrue(prompt.action is PendingImport.Replace)
    assertEquals("Testisuunnitelma", prompt.planName)
    assertNull("nothing is reported until it has happened", vm.importFeedback.value)
    assertNull("and nothing is written", repository.getSessions().firstOrNull { it.id == "s-1" })
  }

  @Test
  fun `confirming the pending import carries it out`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()
    vm.importPlanJson(PLAN)
    advanceUntilIdle()

    vm.confirmPendingImport()
    advanceUntilIdle()

    assertNull(vm.pendingImport.value)
    val feedback = vm.importFeedback.value!!
    assertFalse(feedback.isError)
    assertEquals("Suunnitelma tuotu", feedback.title)
    assertEquals(listOf("s-1"), repository.getSessions().map { it.id })
  }

  @Test
  fun `cancelling the pending import writes nothing`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()
    vm.importPlanJson(PLAN)
    advanceUntilIdle()

    vm.cancelPendingImport()
    advanceUntilIdle()

    assertNull(vm.pendingImport.value)
    assertNull(vm.importFeedback.value)
    assertTrue(repository.getSessions().none { it.id == "s-1" })
  }

  /** A broken document is a report, not a question: there is nothing to confirm. */
  @Test
  fun `an invalid document is reported rather than offered`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()

    vm.importPlanJson(PLAN.replace("\"07:00\"", "\"25:00\""))
    advanceUntilIdle()

    assertNull(vm.pendingImport.value)
    assertTrue(vm.importFeedback.value!!.isError)
  }

  @Test
  fun `an empty document is refused without asking anything`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()

    vm.importPlanJson("   ")
    advanceUntilIdle()

    assertNull(vm.pendingImport.value)
    assertEquals("Ei tuotavaa", vm.importFeedback.value!!.title)
  }

  private fun exercise() =
    Exercise(
      name = "Penkkipunnerrus",
      reps = 8,
      guide = GuideRef(GuideProviders.EXERCISEDB, "EIeI8Vf"),
    )

  /**
   * The Oura card has to be able to say "no credentials in this build", because that is what a
   * clone without an `.env` is — and tapping connect there must not open a browser at an empty
   * `client_id`.
   */
  @Test
  fun `a build without Oura credentials offers no login`() = runTest {
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.connectOura()
    advanceUntilIdle()

    assertEquals(OuraConnectionState.NotConfigured, viewModel.ouraState.value)
    assertNull(viewModel.ouraAuthorizationUrl.value)
  }

  private companion object {
    val PLACEHOLDER_CREDENTIALS =
      OuraCredentials(clientId = "placeholder_client_id", clientSecret = "placeholder_client_secret")

    fun guide(id: String, name: String) =
      ExerciseGuide(
        id = id,
        name = name,
        imageUrl = "https://static.invalid/$id.gif",
        instructions = listOf("Lie flat on a bench."),
        targetMuscles = listOf("pectorals"),
        equipment = listOf("barbell"),
        attribution = "Liiketiedot: testi",
      )

    const val PLAN =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-testi",
          "name": "Testisuunnitelma",
          "timeZone": "Europe/Helsinki",
          "startDate": "2026-08-10"
        },
        "weeks": [
          {
            "weekNumber": 1,
            "sessions": [
              {
                "id": "s-1",
                "type": "STRENGTH",
                "date": "2026-08-10",
                "time": "07:00",
                "durationMin": 45,
                "description": "Aamun keskivartalo."
              }
            ]
          }
        ]
      }
      """
  }
}
