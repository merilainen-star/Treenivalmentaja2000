package fi.merilainen.treenivalmentaja

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuideProvider
import fi.merilainen.treenivalmentaja.data.guide.GuideProviders
import fi.merilainen.treenivalmentaja.data.guide.GuideUnavailableException
import fi.merilainen.treenivalmentaja.data.importer.PendingImport
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.oura.FakeOuraTokenStorage
import fi.merilainen.treenivalmentaja.data.oura.OuraAuthService
import fi.merilainen.treenivalmentaja.data.oura.OuraConnection
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraCredentials
import fi.merilainen.treenivalmentaja.data.repository.OuraRepository
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.data.update.UpdateInfo
import fi.merilainen.treenivalmentaja.data.update.UpdateService
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState
import fi.merilainen.treenivalmentaja.domain.GuideRef
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutOutcome
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutPosition
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutPositionState
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutProgressStore
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
import fi.merilainen.treenivalmentaja.domain.MissedProposalDismissalStore
import fi.merilainen.treenivalmentaja.domain.MissedSessionsProposal
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
  private lateinit var clock: MutableClock

  private class MutableClock(
    var currentInstant: Instant,
    private val currentZone: ZoneId,
  ) : Clock() {
    override fun getZone(): ZoneId = currentZone
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentInstant, zone)
    override fun instant(): Instant = currentInstant
    fun advance(duration: Duration) {
      currentInstant = currentInstant.plus(duration)
    }
  }

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
    clock = MutableClock(Instant.parse("2026-08-10T12:00:00Z"), ZoneId.of("UTC"))
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
    repository = TrainingRepository(db, clock)
  }

  @After
  fun tearDown() {
    db.close()
    Dispatchers.resetMain()
  }

  /** An in-memory [MissedProposalDismissalStore]: DataStore's persistence without its IO. */
  private val progressStore = FakeProgressStore()

  private class FakeDismissalStore(var dismissedFor: LocalDate? = null) :
    MissedProposalDismissalStore {
    override suspend fun dismissedFor(): LocalDate? = dismissedFor

    override suspend fun setDismissedFor(date: LocalDate) {
      dismissedFor = date
    }
  }

  /** An in-memory [ActiveWorkoutProgressStore]: DataStore's persistence without its IO. */
  private class FakeProgressStore(var stored: ActiveWorkoutPosition? = null) :
    ActiveWorkoutProgressStore {
    override suspend fun load(): ActiveWorkoutPosition? = stored

    override suspend fun save(position: ActiveWorkoutPosition) {
      stored = position
    }

    override suspend fun clear() {
      stored = null
    }
  }

  private fun viewModel(
    rolloverDispatcher: CoroutineDispatcher = Dispatchers.Default,
    dismissalStore: MissedProposalDismissalStore? = null,
  ): WorkoutViewModel {
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
      engine = TrainingEngine(repository, clock, reschedule),
      clock = clock,
      rolloverDispatcher = rolloverDispatcher,
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
      // Points at a port nothing listens on, and is never reached anyway: syncOura() returns
      // immediately unless Oura is connected, which a placeholder build cannot be.
      ouraRepository =
        OuraRepository(
          client = OuraClient(tokens = { null }, baseUrl = "http://127.0.0.1:1"),
          dao = db.ouraDao(),
        ),
      missedProposalDismissalStore = dismissalStore,
      activeWorkoutProgressStore = progressStore,
    )
  }

  @Test
  fun `refreshCurrentDate crosses midnight without recreating the ViewModel`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(LocalDate.of(2026, 8, 10), vm.currentDate.value)

    clock.advance(Duration.ofDays(1))
    vm.refreshCurrentDate()

    assertEquals(LocalDate.of(2026, 8, 11), vm.currentDate.value)
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

  // ------------------------------------------------------------------ the date, and what hangs off it

  /**
   * The plan's timezone decides what "today" is, not the device clock's.
   *
   * 22:30 UTC is already the 11th in Helsinki. A ViewModel that read the device zone would call it
   * the 10th and disagree with `TrainingEngine`, which resolves the same question against the plan
   * — and the two disagreeing is how a session becomes missed on one screen and not on another.
   */
  @Test
  fun `today comes from the active plan's zone, not the device clock's`() = runTest(dispatcher) {
    clock.currentInstant = Instant.parse("2026-08-10T22:30:00Z")
    repository.importPlan(PLAN)

    val viewModel = viewModel()
    advanceUntilIdle()

    assertEquals(LocalDate.of(2026, 8, 11), viewModel.currentDate.value)
  }

  /**
   * The regression the reactive date exists to prevent, asserted where it actually bit.
   *
   * `todayRecovery` used to bake `LocalDate.now()` into the Room query at construction, so a
   * process alive across midnight kept showing yesterday's reading — on the one screen the app is
   * opened in the morning to look at. This fails on the old code and passes on the new.
   */
  @Test
  fun `today's recovery follows the date across midnight`() = runTest(dispatcher) {
    clock.currentInstant = Instant.parse("2026-08-10T09:00:00Z")
    repository.importPlan(PLAN)
    db.ouraDao().upsertDailySummaries(
      listOf(
        OuraDailySummaryEntity(
          date = "2026-08-10",
          readinessScore = 55,
          fetchedAtUtc = clock.millis(),
        ),
        OuraDailySummaryEntity(
          date = "2026-08-11",
          readinessScore = 88,
          fetchedAtUtc = clock.millis(),
        ),
      )
    )

    val viewModel = viewModel()
    // `stateIn(WhileSubscribed)` holds its initial value until something collects, so the flow has
    // to be subscribed for this assertion to be about the query rather than about the default.
    backgroundScope.launch { viewModel.todayRecovery.collect {} }
    advanceUntilIdle()
    assertEquals(55, viewModel.todayRecovery.value?.readiness)

    clock.advance(Duration.ofDays(1))
    viewModel.refreshCurrentDate()
    advanceUntilIdle()

    assertEquals(88, viewModel.todayRecovery.value?.readiness)
  }

  // ------------------------------------------------------------------ the missed-session proposal

  /** Asking for a proposal is a pure read: the session it names must still be where it was. */
  @Test
  fun `checking for missed sessions writes nothing`() = runTest(dispatcher) {
    clock.currentInstant = Instant.parse("2026-08-12T09:00:00Z")
    repository.importPlan(PLAN)

    val viewModel = viewModel()
    advanceUntilIdle()
    viewModel.checkMissedSessions()
    advanceUntilIdle()

    assertTrue(viewModel.missedSessionsProposal.value is MissedSessionsProposal.MoveOne)
    assertEquals("2026-08-10", repository.getSessions().single().scheduledDate)
  }

  /** Accepting moves the session once; the proposal is spent and a second tap cannot move it again. */
  @Test
  fun `accepting a proposal applies it exactly once`() = runTest(dispatcher) {
    clock.currentInstant = Instant.parse("2026-08-12T09:00:00Z")
    repository.importPlan(PLAN)

    val viewModel = viewModel()
    advanceUntilIdle()
    viewModel.checkMissedSessions()
    advanceUntilIdle()
    val proposal = viewModel.missedSessionsProposal.value as MissedSessionsProposal.MoveOne

    viewModel.acceptMissedSessionsProposal()
    advanceUntilIdle()
    val afterFirst = repository.getSessions().single { it.status.isOpen }.scheduledDate
    assertEquals(proposal.toDate.toString(), afterFirst)

    // The card is gone, so there is nothing left to accept — and re-checking finds nothing either,
    // because the session is no longer in the past.
    assertEquals(MissedSessionsProposal.None, viewModel.missedSessionsProposal.value)
    viewModel.checkMissedSessions()
    advanceUntilIdle()
    assertEquals(MissedSessionsProposal.None, viewModel.missedSessionsProposal.value)
    assertEquals(afterFirst, repository.getSessions().single { it.status.isOpen }.scheduledDate)
  }

  /**
   * "Ei nyt" survives leaving the screen, which is the only thing that makes it an answer.
   *
   * `checkMissedSessions()` runs on every resume of the Today screen. Without a memory of the
   * refusal the card came straight back, so rejecting bought a few seconds of quiet — and the
   * screen the app opens on would have asked the same question every time it was opened.
   */
  @Test
  fun `a rejected proposal writes nothing and stays rejected for the rest of the day`() =
    runTest(dispatcher) {
      clock.currentInstant = Instant.parse("2026-08-12T09:00:00Z")
      repository.importPlan(PLAN)

      val viewModel = viewModel()
      advanceUntilIdle()
      viewModel.checkMissedSessions()
      advanceUntilIdle()

      viewModel.rejectMissedSessionsProposal()
      assertEquals(MissedSessionsProposal.None, viewModel.missedSessionsProposal.value)
      assertEquals("2026-08-10", repository.getSessions().single().scheduledDate)

      // What every later resume of the Today screen does.
      viewModel.checkMissedSessions()
      advanceUntilIdle()

      assertEquals(MissedSessionsProposal.None, viewModel.missedSessionsProposal.value)
    }

  /** "Not today" and not "never": tomorrow is a new day, and the session is still in the past. */
  @Test
  fun `a refusal expires with the day it was given on`() = runTest(dispatcher) {
    clock.currentInstant = Instant.parse("2026-08-12T09:00:00Z")
    repository.importPlan(PLAN)

    val viewModel = viewModel()
    advanceUntilIdle()
    viewModel.checkMissedSessions()
    advanceUntilIdle()
    viewModel.rejectMissedSessionsProposal()

    clock.advance(Duration.ofDays(1))
    viewModel.refreshCurrentDate()
    viewModel.checkMissedSessions()
    advanceUntilIdle()

    assertTrue(viewModel.missedSessionsProposal.value is MissedSessionsProposal.MoveOne)
  }

  /**
   * The third answer: the missed sessions are closed as done, and the card cannot come back.
   *
   * Shifting the plan and refusing both leave the sessions missed, so a backlog of training that
   * will never happen — a plan left half-finished while the app was being written — asked the same
   * question every single day. This is the button that ends it.
   */
  @Test
  fun `marking the missed sessions done closes them and ends the question`() =
    runTest(dispatcher) {
      clock.currentInstant = Instant.parse("2026-08-12T09:00:00Z")
      repository.importPlan(PLAN)

      val viewModel = viewModel()
      advanceUntilIdle()
      viewModel.checkMissedSessions()
      advanceUntilIdle()
      assertTrue(viewModel.missedSessionsProposal.value is MissedSessionsProposal.MoveOne)

      viewModel.completeMissedSessionsProposal()
      advanceUntilIdle()

      assertEquals(SessionStatus.COMPLETED, repository.getSessions().single().status)
      // Unmoved: the session stays on the day it was planned for.
      assertEquals("2026-08-10", repository.getSessions().single().scheduledDate)

      // Not a refusal that expires tomorrow — there is nothing left to propose at all.
      viewModel.checkMissedSessions()
      advanceUntilIdle()
      assertEquals(MissedSessionsProposal.None, viewModel.missedSessionsProposal.value)
    }

  /**
   * A refusal survives the process, which is what "ei nyt" has to mean on a phone.
   *
   * The answer used to live only in this ViewModel, so every new build installed over the app —
   * and every ordinary cold start — asked again about the same sessions on the same day. A fresh
   * ViewModel here is exactly that restart.
   */
  @Test
  fun `a refusal survives a restart on the same day`() = runTest(dispatcher) {
    clock.currentInstant = Instant.parse("2026-08-12T09:00:00Z")
    repository.importPlan(PLAN)
    val store = FakeDismissalStore()

    val first = viewModel(dismissalStore = store)
    advanceUntilIdle()
    first.checkMissedSessions()
    advanceUntilIdle()
    first.rejectMissedSessionsProposal()
    advanceUntilIdle()
    assertEquals(LocalDate.of(2026, 8, 12), store.dismissedFor)

    val restarted = viewModel(dismissalStore = store)
    advanceUntilIdle()
    restarted.checkMissedSessions()
    advanceUntilIdle()

    assertEquals(MissedSessionsProposal.None, restarted.missedSessionsProposal.value)

    // Still "not today" rather than "never": yesterday's answer does not silence tomorrow.
    store.dismissedFor = LocalDate.of(2026, 8, 11)
    val nextDay = viewModel(dismissalStore = store)
    advanceUntilIdle()
    nextDay.checkMissedSessions()
    advanceUntilIdle()
    assertTrue(nextDay.missedSessionsProposal.value is MissedSessionsProposal.MoveOne)
  }

  /**
   * The midnight rollover itself, driven rather than described.
   *
   * This is what the injectable rollover dispatcher exists for. The loop re-arms forever, so on the
   * shared test dispatcher it would spin a fixed clock through the same midnight in every other
   * test in this file; given its own dispatcher it can be advanced one deliberate step. Nothing
   * calls `refreshCurrentDate()` here — the point is that the app left open on the Today screen
   * turns the page by itself.
   */
  @Test
  fun `the date turns at plan-zone midnight with no one touching the app`() = runTest(dispatcher) {
    // 21:00 UTC is 00:00 in Helsinki, so midnight is one hour away in the plan's zone.
    clock.currentInstant = Instant.parse("2026-08-12T20:00:00Z")
    repository.importPlan(PLAN)
    val rolloverDispatcher = StandardTestDispatcher(testScheduler)

    val viewModel = viewModel(rolloverDispatcher)
    // `advanceUntilIdle()` must not appear in this test. The rollover re-arms itself, and a clock
    // that only moves when this test moves it would make "wait until there is nothing left to do"
    // mean "recompute the same midnight forever". Every step here is bounded on purpose.
    advanceTimeBy(Duration.ofMinutes(59).toMillis())
    assertEquals(LocalDate.of(2026, 8, 12), viewModel.currentDate.value)

    // The wall clock reaches midnight, and the coroutine that was waiting for it wakes.
    clock.advance(Duration.ofHours(1))
    advanceTimeBy(Duration.ofMinutes(2).toMillis())

    assertEquals(LocalDate.of(2026, 8, 13), viewModel.currentDate.value)
    // Re-armed for the *next* midnight, a day away, so nothing else fires while this test finishes.
    viewModel.viewModelScope.coroutineContext.cancelChildren()
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

  @Test
  fun `an interrupted workout resumes where it was left, not at the first movement`() =
    runTest(dispatcher) {
      val vm = viewModel()
      advanceUntilIdle()
      // No session needs to exist in the database: the position is read before the session is
      // looked up, precisely so a slow or failed lookup cannot decide where the workout resumes.
      val sessionId = "s-1"
      progressStore.stored = ActiveWorkoutPosition(sessionId, stepIndex = 5, skippedKeys = listOf("1:2"))

      vm.startActiveWorkout(sessionId)
      advanceUntilIdle()

      val state = vm.activeWorkoutPosition.value
      assertTrue(state is ActiveWorkoutPositionState.Ready)
      assertEquals(5, (state as ActiveWorkoutPositionState.Ready).value?.stepIndex)
      assertEquals(listOf("1:2"), state.value?.skippedKeys)
    }

  @Test
  fun `a position stored for another session is not inherited`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()
    val sessionId = "s-1"
    progressStore.stored = ActiveWorkoutPosition("some-other-session", stepIndex = 9)

    vm.startActiveWorkout(sessionId)
    advanceUntilIdle()

    val state = vm.activeWorkoutPosition.value as ActiveWorkoutPositionState.Ready
    assertNull(state.value)
  }

  @Test
  fun `the position is not Ready until it has actually been read`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()

    // Before startActiveWorkout runs there is nothing to report, and the screen must wait rather
    // than draw the first movement and correct itself.
    assertTrue(vm.activeWorkoutPosition.value is ActiveWorkoutPositionState.Loading)
  }

  @Test
  fun `finishing the workout clears the stored position`() = runTest(dispatcher) {
    val vm = viewModel()
    advanceUntilIdle()
    val sessionId = "s-1"
    vm.saveActiveWorkoutPosition(sessionId, stepIndex = 4, skippedKeys = emptyList())
    advanceUntilIdle()
    assertEquals(4, progressStore.stored?.stepIndex)

    vm.completeActiveWorkout(
      sessionId,
      ActiveWorkoutOutcome(guided = GuidedProgress(done = 3, rounds = 1, perRound = 3)),
    )
    advanceUntilIdle()

    assertNull(progressStore.stored)
  }
}
