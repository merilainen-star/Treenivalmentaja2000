package fi.merilainen.treenivalmentaja

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsActivityEntity
import fi.merilainen.treenivalmentaja.data.oura.FakeOuraTokenStorage
import fi.merilainen.treenivalmentaja.data.oura.OuraAuthService
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraConnection
import fi.merilainen.treenivalmentaja.data.oura.OuraCredentials
import fi.merilainen.treenivalmentaja.data.repository.IntervalsRepository
import fi.merilainen.treenivalmentaja.data.repository.OuraRepository
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.data.update.UpdateInfo
import fi.merilainen.treenivalmentaja.data.update.UpdateService
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.domain.EasyRunDrift
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the easy-run drift rule reaches the screen from the database, and not merely from a unit
 * test's hand-built lists.
 *
 * `EasyRunDriftUseCaseTest` proves the rule; this proves it is *wired* — a real Room database, a
 * real plan import, real `intervals_activities` rows matched to sessions, and the ViewModel flow
 * the Today screen collects. The distinction is not academic here: the AI prompt shipped correct
 * and unreachable once already, because it read a `StateFlow` nothing was subscribed to, and every
 * test of the logic passed throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EasyRunDriftWiringTest {

  private val dispatcher = StandardTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: TrainingRepository
  private lateinit var intervals: IntervalsRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
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
    intervals =
      IntervalsRepository(
        // A port nothing listens on: the rule reads the database and never the network.
        client = IntervalsClient(apiKeys = { "test-key" }, baseUrl = "http://127.0.0.1:1"),
        dao = db.intervalsDao(),
      )
  }

  @After
  fun tearDown() {
    db.close()
    Dispatchers.resetMain()
  }

  /** Six easy runs stored, the last three above the median, and an easy one still ahead today. */
  @Test
  fun `a drifting history raises the finding on the Today flow`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      seed(today, intensities = listOf(70.0, 71.0, 72.0, 80.0, 81.0, 82.0))

      val vm = viewModel()
      backgroundScope.launch { vm.easyRunDrift.collect {} }
      advanceUntilIdle()

      val finding = vm.easyRunDrift.value as EasyRunDrift.Finding
      assertEquals("tanaan", finding.sessionId)
      assertEquals(WorkoutType.RUNNING, finding.type)
      assertEquals(listOf(82, 81, 80), finding.recentIntensityPercent)
      assertEquals(76, finding.medianIntensityPercent)
      assertEquals(6, finding.comparableSessions)
    }

  /** "Selvä" puts it away for the rest of the day, exactly as the readiness card's "Ei nyt" does. */
  @Test
  fun `dismissing it clears the card for the day`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      seed(today, intensities = listOf(70.0, 71.0, 72.0, 80.0, 81.0, 82.0))

      val vm = viewModel()
      backgroundScope.launch { vm.easyRunDrift.collect {} }
      advanceUntilIdle()
      assertTrue(vm.easyRunDrift.value is EasyRunDrift.Finding)

      vm.dismissEasyRunDrift()
      advanceUntilIdle()

      assertEquals(EasyRunDrift.None, vm.easyRunDrift.value)
    }

  /** The ordinary state: runs stored, none of them drifting, and the screen stays quiet. */
  @Test
  fun `a steady history produces nothing`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      seed(today, intensities = listOf(70.0, 73.0, 71.0, 72.0, 70.0, 74.0))

      val vm = viewModel()
      backgroundScope.launch { vm.easyRunDrift.collect {} }
      advanceUntilIdle()

      assertEquals(EasyRunDrift.None, vm.easyRunDrift.value)
    }

  /**
   * With no intervals.icu account there are no activities, so there is nothing to compare — the
   * same silence a fresh install produces, and the reason the repository is nullable at all.
   */
  @Test
  fun `no watch data at all produces nothing`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(plan(today, pastRuns = 6))
      completePastRuns(6)
      advanceUntilIdle()

      val vm = viewModel(withIntervals = false)
      backgroundScope.launch { vm.easyRunDrift.collect {} }
      advanceUntilIdle()

      assertEquals(EasyRunDrift.None, vm.easyRunDrift.value)
    }

  // ------------------------------------------------------------------ harness

  /** A plan with [intensities] completed easy runs behind it, each matched to a stored activity. */
  private suspend fun seed(today: LocalDate, intensities: List<Double>) {
    repository.importPlan(plan(today, pastRuns = intensities.size))
    completePastRuns(intensities.size)
    db.intervalsDao()
      .upsertActivities(
        intensities.mapIndexed { index, intensity ->
          val date = today.minusDays((intensities.size - index).toLong())
          IntervalsActivityEntity(
            id = "activity-$index",
            sportType = "Run",
            startTimeUtc = date.toEpochDay() * 86_400_000L,
            movingTimeSec = 3_000,
            distanceMeters = 9_000.0,
            intensity = intensity,
            matchedSessionId = "run-$index",
            fetchedAtUtc = 1L,
          )
        }
      )
  }

  private suspend fun completePastRuns(count: Int) {
    repeat(count) { index ->
      repository.transition("run-$index", SessionStatus.COMPLETED, EventSource.OURA_SYNC)
    }
  }

  private fun viewModel(withIntervals: Boolean = true): WorkoutViewModel {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val settingsStore = NotificationSettingsStore(context)
    val reschedule =
      object :
        RescheduleAlarmsUseCase(
          database = db,
          planDao = db.trainingPlanDao(),
          sessionDao = db.workoutSessionDao(),
          settingsStore = settingsStore,
          resolveReminderUseCase = ResolveReminderUseCase(),
          reminderScheduler =
            object : ReminderScheduler(context) {
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
          service =
            object : UpdateService {
              override suspend fun fetchLatest(): UpdateInfo = error("not asked")
            },
          installedVersionName = "1.0",
        ),
      loadExerciseGuideUseCase = LoadExerciseGuideUseCase(providers = emptyList()),
      ouraConnection =
        OuraConnection(
          store = FakeOuraTokenStorage(),
          authService = OuraAuthService({ CREDENTIALS }),
          credentials = { CREDENTIALS },
          onDisconnected = {},
        ),
      ouraRepository =
        OuraRepository(
          client = OuraClient(tokens = { null }, baseUrl = "http://127.0.0.1:1"),
          dao = db.ouraDao(),
        ),
      intervalsRepository = if (withIntervals) intervals else null,
    )
  }

  private companion object {

    val CREDENTIALS = OuraCredentials(clientId = "id", clientSecret = "secret")

    /**
     * [pastRuns] easy runs on the days before [today], plus one easy run today — the shape the rule
     * is about. The identifiers match what [seed] stores activities against.
     */
    fun plan(today: LocalDate, pastRuns: Int): String {
      val past =
        (0 until pastRuns).joinToString(",\n") { index ->
          val date = today.minusDays((pastRuns - index).toLong())
          """
          {
            "id": "run-$index",
            "type": "RUNNING",
            "date": "$date",
            "time": "08:00",
            "durationMin": 50,
            "intensity": "EASY",
            "description": "Peruslenkki."
          }
          """
        }
      return """
        {
          "schemaVersion": 1,
          "plan": {
            "id": "plan-drift",
            "name": "Kevyiden kiristyminen",
            "timeZone": "Europe/Helsinki",
            "startDate": "${today.minusDays(pastRuns.toLong())}"
          },
          "weeks": [
            {
              "weekNumber": 1,
              "sessions": [
                $past,
                {
                  "id": "tanaan",
                  "type": "RUNNING",
                  "date": "$today",
                  "time": "08:00",
                  "durationMin": 50,
                  "intensity": "EASY",
                  "description": "Peruslenkki."
                }
              ]
            }
          ]
        }
        """
    }
  }
}
