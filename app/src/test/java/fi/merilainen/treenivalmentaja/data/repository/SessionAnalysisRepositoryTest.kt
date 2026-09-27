package fi.merilainen.treenivalmentaja.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.merilainen.treenivalmentaja.data.analysis.*
import fi.merilainen.treenivalmentaja.data.local.*
import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsActivityEntity
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.intervals.FitBuilder
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionAnalysisRepositoryTest {
  private lateinit var db: AppDatabase
  private lateinit var training: TrainingRepository
  private var calls = 0
  private var sent = ""
  private var gate: CompletableDeferred<Unit>? = null
  private val model = AnalysisModel.DEFAULT
  private fun repository(intervals: IntervalsRepository? = null) = SessionAnalysisRepository(db.analysisDao(), training,
    OuraRepository(OuraClient(tokens = { null }), db.ouraDao()), intervals,
    mapOf(model.provider to object : AnalysisClient {
      override suspend fun analyse(prompt: String, model: AnalysisModel, task: AnalysisTask): String {
        calls++; sent = prompt; gate?.await(); return "Tallennettu analyysi"
      }
    }))
  @Before fun setup() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    training = TrainingRepository(db)
  }
  @After fun close() { db.close() }
  private suspend fun seed() {
    val result = training.importPlan("""{
      "schemaVersion":1,"plan":{"id":"test","name":"Test","timeZone":"Europe/Helsinki","startDate":"2026-09-27"},
      "weeks":[{"weekNumber":1,"sessions":[{"id":"run","type":"RUNNING","date":"2026-09-27","time":"12:00","durationMin":30,
      "runSteps":[{"name":"Veto 1/2","distanceMeters":400,"paceSecPerKm":260},{"name":"Palautus","durationSec":90}]}]}]}
    """)
    assertNotNull(result)
    assertNotNull(training.getSession("run"))
  }
  @Test fun `saved result survives repository recreation and automatic repeat is free`() = runTest {
    seed()
    assertTrue(repository().analyse("run", AiAnalysisKind.COMPLETED, model, automatic = true))
    val reopened = repository()
    assertFalse(reopened.analyse("run", AiAnalysisKind.COMPLETED, model, automatic = true))
    assertEquals(1, calls)
    assertEquals("Tallennettu analyysi", (reopened.states.first()["run"] as AiAnalysisState.Loaded).text)
    assertTrue(sent.contains("400 m"))
    assertTrue(sent.contains("Kilometriväliajat eivät ole vetokohtaisia"))
  }
  @Test fun `interrupted receipt prevents automatic rebilling and explains manual recovery`() = runTest {
    seed()
    db.analysisDao().save(AnalysisRecord("run", "COMPLETED", StoredAnalysisState.IN_FLIGHT, "", "prompt", model.name, 1))
    val service = repository()
    assertFalse(service.analyse("run", AiAnalysisKind.COMPLETED, model, automatic = true))
    assertTrue(service.states.first()["run"] is AiAnalysisState.Failed)
    assertEquals(0, calls)
  }
  @Test fun `concurrent manual and automatic requests make one provider call`() = runTest {
    seed()
    gate = CompletableDeferred()
    val service = repository()
    val first = async { service.analyse("run", AiAnalysisKind.COMPLETED, model) }
    while (calls == 0) delay(10)
    assertFalse(service.analyse("run", AiAnalysisKind.COMPLETED, model, automatic = true))
    gate!!.complete(Unit)
    assertTrue(first.await())
    assertEquals(1, calls)
  }
  @Test fun `undo is audited and remains suppressed after another detection`() = runTest {
    seed()
    val original = training.getSession("run")!!
    assertTrue(training.autoCompleteRun(original, "watch"))
    assertEquals(listOf("run"), training.observeAutoCompletedIds().first())
    assertTrue(training.undoAutoCompletion("run"))
    assertEquals(original.status, training.getSession("run")!!.status)
    assertFalse(training.autoCompleteRun(training.getSession("run")!!, "watch"))
    assertTrue(training.observeAutoCompletedIds().first().isEmpty())
    assertEquals(1, training.getEvents("run").count { it.source == EventSource.INTERVALS_SYNC })
  }
  @Test fun `changed plan invalidates a previously computed automatic decision`() = runTest {
    seed()
    val original = training.getSession("run")!!
    training.transition("run", SessionStatus.SKIPPED)
    assertFalse(training.autoCompleteRun(original, "watch"))
    assertEquals(SessionStatus.SKIPPED, training.getSession("run")!!.status)
  }

  @Test fun `manual request fetches missing FIT before billing and can retry without a receipt`() = runTest {
    seed()
    db.intervalsDao().upsertActivities(listOf(IntervalsActivityEntity(
      id = "old-watch", sportType = "Run", startTimeUtc = 1, movingTimeSec = 1800,
      matchedSessionId = "run", fetchedAtUtc = 1)))
    var fileStatus = 503
    var requests = 0
    val bytes = FitBuilder().lapDefinition(0).lap(0, 116_600, 40_000, 150, 160)
      .lap(0, 90_000, 20_000, 135, 140).build()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/activity/old-watch/file") { exchange ->
      requests++
      exchange.sendResponseHeaders(fileStatus, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val intervals = IntervalsRepository(IntervalsClient({ "test-only" }, "http://127.0.0.1:${server.address.port}"), db.intervalsDao())
      val service = repository(intervals)
      assertFalse(service.analyse("run", AiAnalysisKind.COMPLETED, model))
      assertEquals(0, calls)
      assertFalse(service.hasAttempt("run"))
      assertTrue(service.states.first()["run"] is AiAnalysisState.Failed)
      fileStatus = 200
      assertTrue(service.analyse("run", AiAnalysisKind.COMPLETED, model))
      assertEquals(1, calls)
      assertTrue(sent, sent.contains("Veto 1/2: 1:56,6 · 400 m"))
      assertTrue(sent, sent.contains("tavoiteaika 1:44,0"))
      assertEquals(2, requests)
      // A new manual analysis reads the cached laps, including after process/repository recreation.
      assertTrue(repository(intervals).analyse("run", AiAnalysisKind.COMPLETED, model))
      assertEquals(2, calls)
      assertEquals(2, requests)
      assertTrue(sent.contains("Kellon kierrokset"))
    } finally { server.stop(0) }
  }
}
