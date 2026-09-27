package fi.merilainen.treenivalmentaja.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.data.automation.TrainingAutomation
import fi.merilainen.treenivalmentaja.data.analysis.*
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.intervals.FitBuilder
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.settings.*
import fi.merilainen.treenivalmentaja.domain.*
import java.net.InetSocketAddress
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrainingAutomationTest {
  private lateinit var db: AppDatabase
  private lateinit var server: HttpServer
  private var reads = 0
  private var analyses = 0
  private var fileStatus = 200
  private var fileBytes = "[]".toByteArray()
  private val notifications = mutableListOf<String>()
  private val context get() = ApplicationProvider.getApplicationContext<Application>()
  private val now = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneId.of("Europe/Helsinki"))
  @Before fun setup() {
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      reads++
      if (exchange.requestURI.path.endsWith("/file")) {
        exchange.sendResponseHeaders(fileStatus, fileBytes.size.toLong())
        exchange.responseBody.use { it.write(fileBytes) }
        return@createContext
      }
      val body = if (exchange.requestURI.path.endsWith("/activities")) """[{"id":"watch","type":"Run","start_date":"2026-09-27T09:00:00Z","moving_time":1800,"distance":5000,"source":"SUUNTO"}]""" else "[]"
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
  }
  @After fun close() { server.stop(0); db.close() }
  @Test fun `sync completes run analyses once notifies and opt out stops requests`() = runTest {
    val training = TrainingRepository(db, now)
    training.importPlan("""{"schemaVersion":1,"plan":{"id":"p","name":"Test","timeZone":"Europe/Helsinki","startDate":"2026-09-27"},"weeks":[{"weekNumber":1,"sessions":[{"id":"r","type":"RUNNING","date":"2026-09-27","time":"12:00","durationMin":30,"runSteps":[{"name":"Kevyt","durationSec":1800}]}]}]}""")
    val settings = AutomationSettingsStore(context)
    settings.set(AutomationSettings(completeRuns = true, analyseRuns = true, exportRuns = false))
    val modelSettings = AnalysisSettingsStore(context)
    val model = modelSettings.modelFlow.first()
    val client = object : AnalysisClient {
      override suspend fun analyse(prompt: String, model: AnalysisModel, task: AnalysisTask): String {
        analyses++; assertTrue(prompt.contains("Intervals.icu")); return "Valmis"
      }
    }
    val intervals = IntervalsRepository(IntervalsClient({ "test-only" }, "http://127.0.0.1:${server.address.port}"), db.intervalsDao())
    val stored = SessionAnalysisRepository(db.analysisDao(), training,
      OuraRepository(OuraClient(tokens = { null }), db.ouraDao()), intervals, mapOf(model.provider to client), clock = now)
    val coordinator = TrainingAutomation(training, intervals, stored, settings, modelSettings,
      { setOf(model.provider) }, { true }, { notifications += it }, now)
    assertTrue(coordinator.run())
    assertEquals(SessionStatus.COMPLETED, training.getSession("r")!!.status)
    assertEquals(listOf("r"), notifications)
    assertTrue(coordinator.run())
    assertEquals(1, analyses)
    assertEquals(1, notifications.size)
    settings.set(AutomationSettings(false, false, false))
    val before = reads
    assertTrue(coordinator.run())
    assertEquals(before, reads)
  }

  @Test fun `automatic analysis waits for FIT and sends measured repetitions once`() = runTest {
    val training = TrainingRepository(db, now)
    training.importPlan("""{"schemaVersion":1,"plan":{"id":"p","name":"Test","timeZone":"Europe/Helsinki","startDate":"2026-09-27"},"weeks":[{"weekNumber":1,"sessions":[{"id":"r","type":"RUNNING","date":"2026-09-27","time":"12:00","durationMin":30,"runSteps":[{"name":"Lämmittely","durationSec":720},{"name":"Reipas 1/3","durationSec":360,"paceSecPerKm":305}]}]}]}""")
    val settings = AutomationSettingsStore(context)
    settings.set(AutomationSettings(true, true, false))
    val modelSettings = AnalysisSettingsStore(context)
    val model = modelSettings.modelFlow.first()
    val client = object : AnalysisClient {
      override suspend fun analyse(prompt: String, model: AnalysisModel, task: AnalysisTask): String {
        analyses++
        assertTrue(prompt, prompt.contains("Reipas 1/3: 6:00,0 · 1200 m · 5:00 /km"))
        return "Valmis"
      }
    }
    val intervals = IntervalsRepository(IntervalsClient({ "test-only" }, "http://127.0.0.1:${server.address.port}"), db.intervalsDao())
    val stored = SessionAnalysisRepository(db.analysisDao(), training,
      OuraRepository(OuraClient(tokens = { null }), db.ouraDao()), intervals, mapOf(model.provider to client), clock = now)
    val coordinator = TrainingAutomation(training, intervals, stored, settings, modelSettings,
      { setOf(model.provider) }, { true }, { notifications += it }, now)
    fileStatus = 503
    assertTrue(coordinator.run())
    assertEquals(SessionStatus.COMPLETED, training.getSession("r")!!.status)
    assertEquals(0, analyses)
    assertFalse(stored.hasAttempt("r"))
    assertTrue(notifications.isEmpty())
    fileStatus = 200
    fileBytes = FitBuilder().lapDefinition(0).lap(0, 720_000, 200_000, 130, 140)
      .lap(0, 360_000, 120_000, 150, 160).build()
    assertTrue(coordinator.run())
    assertTrue(coordinator.run())
    assertEquals(1, analyses)
    assertEquals(listOf("r"), notifications)
  }
}
