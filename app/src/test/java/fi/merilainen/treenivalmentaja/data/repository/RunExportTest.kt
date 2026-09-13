package fi.merilainen.treenivalmentaja.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.intervals.toPlannedRunEvent
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.domain.*
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RunExportTest {
  private lateinit var db: AppDatabase
  private lateinit var server: HttpServer
  private lateinit var repository: IntervalsRepository
  private val json = Moshi.Builder().build().adapter(Any::class.java)
  private val events = linkedMapOf<String, Map<String, Any?>>()
  private val writes = mutableListOf<Pair<String, String>>()
  private var status = 200
  private var emptySteps = false
  private var pushError = false
  private val today = LocalDate.of(2026, 9, 13)
  private fun run(id: String = "run") = TrainingSession(
    id, "plan", WorkoutType.RUNNING, 1, today.toString(), "00:30", 0,
    runSteps = listOf(RunStep("Lämmittely", durationSec = 600), RunStep("Veto", distanceMeters = 1000, paceSecPerKm = 300), RunStep("Palautus", durationSec = 120)),
  )

  @Before fun setup() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      var response: Any = events.values.toList()
      if (exchange.requestMethod != "GET") {
        writes += exchange.requestURI.toString() to body
        @Suppress("UNCHECKED_CAST")
        val rows = json.fromJson(body) as List<Map<String, Any?>>
        if (exchange.requestMethod == "PUT") {
          rows.forEach { events.remove(it["external_id"]) }
          response = rows.size
        } else {
          response = rows.map { row ->
            val saved = row + mapOf("workout_doc" to mapOf("steps" to if (emptySteps) emptyList() else
              (row["description"] as String).lines().filter { it.startsWith("- ") }.map { mapOf("duration" to 600) }),
              "push_errors" to if (pushError) listOf(mapOf("service" to "Suunto", "message" to "failed")) else null)
            events[row["external_id"] as String] = saved
            saved
          }
        }
      }
      val bytes = json.toJson(response).toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    repository = IntervalsRepository(IntervalsClient({ "fake-test-key" }, "http://127.0.0.1:${server.address.port}"), db.intervalsDao())
  }
  @After fun teardown() { server.stop(0); db.close() }

  @Test fun `test export and regular reconciliation cannot remove each other`() = runTest {
    repository.exportRuns(listOf(run()), today)
    val original = events.toMap()
    val future = run("trial").copy(scheduledDate = today.plusDays(30).toString())
    assertEquals(RunExportResult.Success(1, 0), repository.exportTestRun(future, today))
    assertEquals(RunExportResult.Success(1, 0), repository.exportTestRun(future, today))
    assertEquals(2, events.size)
    val trial = events.entries.single { it.key.startsWith("treenivalmentaja-test-run-") }
    assertEquals("2026-09-13T00:00:00", trial.value["start_date_local"])
    original.forEach { (key, value) -> assertEquals(value, events[key]) }
    repository.exportRuns(listOf(run()), today)
    assertEquals(trial.value, events[trial.key])
  }

  @Test fun `test export rejects strength or missing stages before writing`() = runTest {
    assertTrue(repository.exportTestRun(run().copy(type = WorkoutType.STRENGTH), today) is RunExportResult.Failure)
    assertTrue(repository.exportTestRun(run().copy(runSteps = null), today) is RunExportResult.Failure)
    assertTrue(writes.isEmpty())
  }

  @Test fun `exports run stages and local date but no strength past or closed sessions`() = runTest {
    val sessions = listOf(run(), run("strength").copy(type = WorkoutType.STRENGTH), run("past").copy(scheduledDate = today.minusDays(1).toString()),
      run("later").copy(scheduledDate = today.plusDays(7).toString())) +
      listOf(SessionStatus.COMPLETED, SessionStatus.STARTED, SessionStatus.CANCELLED, SessionStatus.PAUSED_DUE_TO_ILLNESS).map { run(it.name).copy(status = it) }
    assertEquals(RunExportResult.Success(1, 0), repository.exportRuns(sessions, today))
    val event = events.values.single()
    assertEquals("Run", event["type"])
    assertEquals("2026-09-13T00:30:00", event["start_date_local"])
    val description = event["description"] as String
    assertTrue(description, description.contains("- \"Lämmittely\" 600s"))
    assertTrue(description, description.contains("- \"Veto\" 1000mtr 5:00/km Pace"))
    assertTrue(description, description.contains("- \"Palautus\" 120s"))
    assertTrue(writes.single().first.endsWith("/events/bulk?upsert=true"))
  }

  @Test fun `watch cue stays on each bullet and numeric instructions stay quoted`() = runTest {
    val session = run().copy(runSteps = listOf(
      RunStep("Kiihdytys 1/3 - rento, ei sprintti", durationSec = 15),
      RunStep("Veto 1/6 - 400 m, tavoite 1:44-1:46", distanceMeters = 400, paceSecPerKm = 265),
      RunStep("Palautus - \"kevyt\"", durationSec = 90),
    ))
    assertEquals(RunExportResult.Success(1, 0), repository.exportTestRun(session, today))
    assertEquals(listOf(
      "- \"Kiihdytys 1/3 - rento, ei sprintti\" 15s",
      "- \"Veto 1/6 - 400 m, tavoite 1:44-1:46\" 400mtr 4:25/km Pace",
      "- \"Palautus - 'kevyt'\" 90s",
    ), (events.values.single()["description"] as String).lines().filter { it.isNotBlank() })
  }

  @Test fun `repeated export updates same event and retains changed pace`() = runTest {
    repository.exportRuns(listOf(run()), today)
    val edited = run().copy(runSteps = listOf(RunStep("Kevyt", durationSec = 1200, paceSecPerKm = 360)))
    assertEquals(RunExportResult.Success(1, 0), repository.exportRuns(listOf(edited), today))
    assertEquals(1, events.size)
    assertTrue((events.values.single()["description"] as String).contains("6:00/km Pace"))
  }

  @Test fun `reschedule cleans only owned future runs leaving other calendar content and past alone`() = runTest {
    repository.exportRuns(listOf(run()), today)
    val foreign = mapOf("external_id" to "other-app", "type" to "Run", "category" to "WORKOUT", "start_date_local" to "2026-09-13T00:00:00")
    events["other-app"] = foreign
    val past = run("past").toPlannedRunEvent().externalId
    events[past] = foreign + mapOf("external_id" to past, "start_date_local" to "2026-09-12T00:00:00")
    val moved = run("moved").copy(scheduledDate = today.plusDays(1).toString())
    assertEquals(RunExportResult.Success(1, 1), repository.exportRuns(listOf(moved), today))
    assertEquals(3, events.size)
    assertEquals(foreign, events["other-app"])
    assertTrue(events.containsKey(past))
    assertFalse(events.containsKey(run().toPlannedRunEvent().externalId))
  }

  @Test fun `missing or invalid steps prevent all writes`() = runTest {
    assertTrue(repository.exportRuns(listOf(run(), run("missing").copy(runSteps = null)), today) is RunExportResult.Failure)
    assertTrue(repository.exportRuns(listOf(run().copy(runSteps = listOf(RunStep("invalid", durationSec = -1)))), today) is RunExportResult.Failure)
    assertTrue(writes.isEmpty())
  }

  @Test fun `empty schedule cleans previously exported cancelled runs`() = runTest {
    repository.exportRuns(listOf(run()), today)
    assertEquals(RunExportResult.Success(0, 1), repository.exportRuns(emptyList(), today))
    assertTrue(events.isEmpty())
  }

  @Test fun `unparsed steps and downstream push failure never report success`() = runTest {
    emptySteps = true
    assertTrue(repository.exportRuns(listOf(run()), today) is RunExportResult.Failure)
    emptySteps = false
    pushError = true
    assertTrue(repository.exportRuns(listOf(run()), today) is RunExportResult.Failure)
  }

  @Test fun `authentication failure stops before writes`() = runTest {
    status = 401
    assertTrue(repository.exportRuns(listOf(run()), today) is RunExportResult.Failure)
    assertTrue(writes.isEmpty())
  }
}
