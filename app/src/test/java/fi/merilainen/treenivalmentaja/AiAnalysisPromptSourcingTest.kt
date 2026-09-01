package fi.merilainen.treenivalmentaja

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisClient
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.local.entity.OuraWorkoutEntity
import fi.merilainen.treenivalmentaja.data.oura.FakeOuraTokenStorage
import fi.merilainen.treenivalmentaja.data.oura.OuraAuthService
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraConnection
import fi.merilainen.treenivalmentaja.data.oura.OuraCredentials
import fi.merilainen.treenivalmentaja.data.repository.OuraRepository
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.data.update.UpdateInfo
import fi.merilainen.treenivalmentaja.data.update.UpdateService
import fi.merilainen.treenivalmentaja.domain.AiAnalysisState
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.ResolveReminderUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the analysis prompt gets its data from.
 *
 * **This file exists because of a shipped bug that no other test could have caught.** The prompt was
 * built from `recoveryByDay.value` — a `stateIn(..., WhileSubscribed(5_000))` flow that holds an
 * empty map unless something is actively collecting it. Only the Week screen ever collects it, so
 * tapping the analysis button on the Today screen sent a prompt with **no recovery data at all**: no
 * error, no empty section, just an analysis reasoning from less than the app knew. The model spotted
 * the gap and said so in its answer; nothing in the app did.
 *
 * Every prompt-builder test passed throughout, because the builder was handed an empty map and
 * correctly rendered nothing from it. The bug was in *what it was handed*, which is exactly the seam
 * this file covers: **no screen is composed here and nothing collects any StateFlow**, so a prompt
 * that depends on a subscriber cannot pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiAnalysisPromptSourcingTest {

  private val dispatcher = StandardTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var repository: TrainingRepository

  /** Captures what the ViewModel actually sent, which is the thing under test. */
  private class CapturingClient : AnalysisClient {
    var prompt: String? = null

    override suspend fun analyse(prompt: String, model: AnalysisModel): String {
      this.prompt = prompt
      return "Vastaus."
    }
  }

  private val client = CapturingClient()

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
  }

  @After
  fun tearDown() {
    db.close()
    Dispatchers.resetMain()
  }

  /**
   * The regression, stated as plainly as it can be: an upcoming session, real recovery rows in the
   * database, nothing subscribed to anything, and the prompt must still carry the readings.
   */
  @Test
  fun `an upcoming analysis carries recovery even though no screen is observing it`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(planWithSessionOn(today))
      seedRecovery(today)
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()

      // Deliberately no collection of recoveryByDay, runMetrics or completedMetrics — this is the
      // Today screen's situation, and it is what broke.
      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val prompt = client.prompt
      assertNotNull("the client was never called at all", prompt)
      assertTrue(
        "the recovery section is missing entirely",
        prompt!!.contains("Palautumisen kehitys"),
      )
      assertTrue("the readiness score did not reach the prompt", prompt.contains("palautuminen 84"))
      assertTrue("the HRV measurement did not reach the prompt", prompt.contains("HRV 61 ms"))
      assertTrue("the resting heart rate did not reach the prompt", prompt.contains("leposyke 48"))
    }

  /** What the panel shows is the string that was sent, not a rebuild of it. */
  @Test
  fun `the shown request is the request that was sent`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(planWithSessionOn(today))
      seedRecovery(today)
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()
      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val state = vm.aiAnalyses.value[SESSION_ID] as AiAnalysisState.Loaded
      assertEquals(client.prompt, state.prompt)
      assertEquals("Vastaus.", state.text)
    }

  /** A day the ring was not worn stays absent rather than arriving as zeros. */
  @Test
  fun `a day with no measurements produces no line`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(planWithSessionOn(today))
      db.ouraDao()
        .upsertDailySummaries(
          listOf(
            OuraDailySummaryEntity(date = today.toString(), readinessScore = 84, fetchedAtUtc = 1L),
            // Present in the database, empty of numbers — the ring was off that night.
            OuraDailySummaryEntity(date = today.minusDays(1).toString(), fetchedAtUtc = 1L),
          )
        )
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()
      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val prompt = client.prompt!!
      assertTrue(prompt.contains("$today: palautuminen 84"))
      assertTrue(
        "an empty day must not appear at all",
        !prompt.contains(today.minusDays(1).toString()),
      )
    }

  /**
   * The guided workout, end to end: tick every movement, press "Valmis", ask for the analysis.
   *
   * **This is the seam that matters and the one nothing else covers.** The counter used to live in
   * the card's own `rememberSaveable` and go no further, so the completion recorded that a button
   * was pressed and nothing about what had been done — and the model, correctly, reported the
   * movements, reps and loads as unknown. Every prompt-builder test passed throughout, because the
   * builder was handed no progress and faithfully rendered none.
   *
   * No screen is composed here. What is asserted is that the value the card reports up survives the
   * completion, the database, and the rebuild of the prompt days later.
   */
  @Test
  fun `a fully ticked guided workout reaches the prompt as carried out`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(guidedPlanOn(today))
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()

      // What the card reports as the last box is ticked.
      vm.recordGuidedProgress(SESSION_ID, GuidedProgress(done = 4, rounds = 2, perRound = 2))
      vm.updateWorkoutStatus(SESSION_ID, SessionStatus.COMPLETED)
      advanceUntilIdle()

      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val prompt = client.prompt
      assertNotNull("the client was never called at all", prompt)
      // The plan's own movements, which never used to be sent at all.
      assertTrue(
        "the planned movements did not reach the prompt",
        prompt!!.contains("- Penkkipunnerrus: 3 sarjaa × 8 toistoa, 40 kg"),
      )
      assertTrue("the rounds did not reach the prompt", prompt.contains("2 kierrosta"))
      // And what was actually done with them.
      assertTrue(
        "the completion was not recorded as carried out",
        prompt.contains("Ohjelma toteutui suunnitellusti"),
      )
    }

  /** Stopping early has to survive the same path, and say so rather than going quiet. */
  @Test
  fun `an abandoned guided workout reaches the prompt as unfinished`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(guidedPlanOn(today))
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()

      vm.recordGuidedProgress(SESSION_ID, GuidedProgress(done = 3, rounds = 2, perRound = 2))
      vm.updateWorkoutStatus(SESSION_ID, SessionStatus.COMPLETED)
      advanceUntilIdle()

      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val prompt = client.prompt!!
      assertTrue(prompt.contains("3 / 4 liikesuoritusta kuitattiin tehdyksi"))
      assertTrue(prompt.contains("- Kierros 2: tehty Kissanlehmä; tekemättä Penkkipunnerrus"))
    }

  /**
   * A session completed without the guided workout ever reporting anything must not acquire a
   * zero. "Nothing recorded" and "nothing done" are different facts, and only one of them is true.
   */
  @Test
  fun `a completion with no guided progress sends no guided section`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(guidedPlanOn(today))
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()
      vm.updateWorkoutStatus(SESSION_ID, SessionStatus.COMPLETED)
      advanceUntilIdle()

      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val prompt = client.prompt!!
      assertTrue("the plan itself should still be sent", prompt.contains("Suunniteltu ohjelma"))
      assertTrue(
        "an unrecorded workout must not report movements as undone",
        !prompt.contains("liikesuoritusta kuitattiin"),
      )
    }

  /**
   * The report that started this file's newest test: a session `SKIPPED` in the app's own record,
   * with real Oura data attached anyway — because matching a workout to a session never changes
   * its status (`docs/TRAINING_ENGINE.md`, "Matching imported workouts"). The button has to reach
   * a session like this exactly as it would a `COMPLETED` one, and the request has to actually go
   * out rather than the gate inside `requestAiAnalysis` silently disagreeing with it.
   */
  @Test
  fun `a skipped session Oura matched something to can still be analysed`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(planWithSessionOn(today))
      seedRecovery(today)
      val startUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 9 * 3_600_000L
      db.ouraDao()
        .upsertWorkouts(
          listOf(
            OuraWorkoutEntity(
              id = "oura-w-1",
              activityType = "strengthTraining",
              startTimeUtc = startUtc,
              endTimeUtc = startUtc + 14 * 60_000L,
              calories = 66f,
              matchedSessionId = SESSION_ID,
            )
          )
        )
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()
      vm.updateWorkoutStatus(SESSION_ID, SessionStatus.SKIPPED)
      advanceUntilIdle()

      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      val prompt = client.prompt
      assertNotNull("the client was never called — the gate rejected an eligible session", prompt)
      assertTrue("the matched duration did not reach the prompt", prompt!!.contains("kesto 14 min"))
      assertTrue("the matched calories did not reach the prompt", prompt.contains("66 kcal"))
    }

  /** The other half of the same fact: nothing recorded really does mean nothing to ask about. */
  @Test
  fun `a skipped session with no match offers nothing to analyse`() =
    runTest(dispatcher) {
      val today = LocalDate.now()
      repository.importPlan(planWithSessionOn(today))
      advanceUntilIdle()

      val vm = viewModel()
      advanceUntilIdle()
      vm.updateWorkoutStatus(SESSION_ID, SessionStatus.SKIPPED)
      advanceUntilIdle()

      vm.requestAiAnalysis(SESSION_ID)
      advanceUntilIdle()

      assertEquals(null, vm.aiAnalyses.value[SESSION_ID])
    }

  // ------------------------------------------------------------------ harness

  private suspend fun seedRecovery(today: LocalDate) {
    db.ouraDao()
      .upsertDailySummaries(
        (0L..3L).map { back ->
          val day = today.minusDays(back)
          OuraDailySummaryEntity(
            date = day.toString(),
            readinessScore = if (back == 0L) 84 else 70 + back.toInt(),
            sleepScore = 86,
            averageHrvMs = if (back == 0L) 61 else 55,
            restingHrBpm = if (back == 0L) 48 else 50,
            fetchedAtUtc = 1L,
          )
        }
      )
  }

  private fun viewModel(): WorkoutViewModel {
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
      // The guide is never opened here; an empty provider list is enough to construct it.
      loadExerciseGuideUseCase = LoadExerciseGuideUseCase(providers = emptyList()),
      ouraConnection =
        OuraConnection(
          store = FakeOuraTokenStorage(),
          authService = OuraAuthService({ CREDENTIALS }),
          credentials = { CREDENTIALS },
          onDisconnected = {},
        ),
      // Points at a port nothing listens on. The analysis reads the database, never the network.
      ouraRepository =
        OuraRepository(
          client = OuraClient(tokens = { null }, baseUrl = "http://127.0.0.1:1"),
          dao = db.ouraDao(),
        ),
      // The default model is a Claude one, so this is the client the request routes to.
      analysisClients = mapOf(AnalysisProvider.ANTHROPIC to client),
    )
  }

  private companion object {
    const val SESSION_ID = "s-1"

    val CREDENTIALS = OuraCredentials(clientId = "id", clientSecret = "secret")

    /**
     * A strength session the guided workout can actually be walked through: two movements, two
     * rounds, and a load on one of them so the prompt has a number to carry.
     */
    fun guidedPlanOn(day: LocalDate): String =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-ohjattu",
          "name": "Ohjattu treeni",
          "timeZone": "Europe/Helsinki",
          "startDate": "$day"
        },
        "weeks": [
          {
            "weekNumber": 1,
            "sessions": [
              {
                "id": "$SESSION_ID",
                "type": "STRENGTH",
                "date": "$day",
                "time": "09:00",
                "durationMin": 20,
                "intensity": "MODERATE",
                "rounds": 2,
                "description": "Voima A.",
                "exercises": [
                  { "name": "Kissanlehmä", "reps": 10 },
                  { "name": "Penkkipunnerrus", "sets": 3, "reps": 8, "weightKg": 40.0 }
                ]
              }
            ]
          }
        ]
      }
      """

    /** One planned strength session on the given day, so it falls inside the upcoming window. */
    fun planWithSessionOn(day: LocalDate): String =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-analyysi",
          "name": "Analyysitesti",
          "timeZone": "Europe/Helsinki",
          "startDate": "$day"
        },
        "weeks": [
          {
            "weekNumber": 1,
            "sessions": [
              {
                "id": "$SESSION_ID",
                "type": "STRENGTH",
                "date": "$day",
                "time": "09:00",
                "durationMin": 20,
                "intensity": "MODERATE",
                "description": "Voima A."
              }
            ]
          }
        ]
      }
      """
  }
}
