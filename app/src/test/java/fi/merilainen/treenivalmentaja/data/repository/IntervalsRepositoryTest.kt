package fi.merilainen.treenivalmentaja.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsClient
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.domain.PlannedSession
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole path, from an HTTP response to a row a screen can observe.
 *
 * A real client against a local server and a real in-memory Room database, for the reason
 * [OuraRepositoryTest] uses them: the interesting failures live between the parts rather than
 * inside them — an activity fetched twice becoming one row, a failed sync leaving what was already
 * stored alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntervalsRepositoryTest {

  private val dispatcher = StandardTestDispatcher()
  private val zone = ZoneId.of("Europe/Helsinki")

  private lateinit var db: AppDatabase
  private lateinit var server: HttpServer
  private lateinit var repository: IntervalsRepository

  private var status = 200
  private var body = "[]"

  @Before
  fun setUp() {
    db =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AppDatabase::class.java,
        )
        .setQueryExecutor(dispatcher.asExecutor())
        .setTransactionExecutor(dispatcher.asExecutor())
        .allowMainThreadQueries()
        .build()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange: HttpExchange ->
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    repository =
      IntervalsRepository(
        client =
          IntervalsClient(
            apiKeys = { "test-key" },
            baseUrl = "http://127.0.0.1:${server.address.port}",
          ),
        dao = db.intervalsDao(),
        clock = { FETCHED_AT },
      )
  }

  @After
  fun tearDown() {
    server.stop(0)
    db.close()
  }

  @Test
  fun `a sync stores what came back`() = runTest(dispatcher) {
    body = oneRun(id = "i1")

    val result = repository.sync(FROM, TO, zone)

    assertEquals(IntervalsSyncResult.Success(activities = 1), result)
    assertEquals(1, db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE).size)
  }

  /**
   * The idempotence guarantee, and the reason rows are keyed on the service's own activity id.
   *
   * Every sync re-fetches an overlapping window on purpose — an activity can reach intervals.icu
   * late — so the same activity arrives again and again. It must rewrite its row rather than
   * appear as a second training session.
   */
  @Test
  fun `the same activity fetched twice is one row`() = runTest(dispatcher) {
    body = oneRun(id = "i1")
    repository.sync(FROM, TO, zone)
    repository.sync(FROM, TO, zone)

    val rows = db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE)

    assertEquals(1, rows.size)
    assertEquals("i1", rows.single().id)
  }

  /** A corrected activity overwrites its own row rather than sitting beside the stale one. */
  @Test
  fun `a re-fetched activity is updated in place`() = runTest(dispatcher) {
    body = oneRun(id = "i1", movingTime = 2280)
    repository.sync(FROM, TO, zone)
    body = oneRun(id = "i1", movingTime = 2400)
    repository.sync(FROM, TO, zone)

    val rows = db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE)

    assertEquals(1, rows.size)
    assertEquals(2400L, rows.single().movingTimeSec)
  }

  @Test
  fun `several activities all land, each as its own row`() = runTest(dispatcher) {
    body =
      """[${oneRunBody("i1", "2026-08-13T06:00:00Z")},
          ${oneRunBody("i2", "2026-08-14T06:00:00Z")},
          ${oneRunBody("i3", "2026-08-15T06:00:00Z")}]"""

    repository.sync(FROM, TO, zone)

    assertEquals(3, db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE).size)
  }

  /**
   * The offline case. A sync that could not reach the service must leave what is already stored
   * exactly as it was — the screens observe the database, so anything else would blank the history
   * because a phone went through a tunnel.
   */
  @Test
  fun `a failed sync leaves stored rows alone`() = runTest(dispatcher) {
    body = oneRun(id = "i1")
    repository.sync(FROM, TO, zone)

    status = 503
    val result = repository.sync(FROM, TO, zone)

    assertTrue(result.toString(), result is IntervalsSyncResult.Failure)
    assertTrue((result as IntervalsSyncResult.Failure).canRetry)
    assertEquals(1, db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE).size)
  }

  @Test
  fun `a rejected key is a failure that cannot be retried`() = runTest(dispatcher) {
    status = 401

    val result = repository.sync(FROM, TO, zone)

    assertEquals(false, (result as IntervalsSyncResult.Failure).canRetry)
  }

  /** The service's own wait, carried through to whoever decides when to ask again. */
  @Test
  fun `a rate limit carries Retry-After through to the result`() = runTest(dispatcher) {
    server.removeContext("/")
    server.createContext("/") { exchange: HttpExchange ->
      exchange.responseHeaders.add("Retry-After", "90")
      exchange.sendResponseHeaders(429, 2)
      exchange.responseBody.use { it.write("[]".toByteArray()) }
    }

    val result = repository.sync(FROM, TO, zone) as IntervalsSyncResult.Failure

    assertEquals(90L, result.retryAfterSeconds)
    assertTrue(result.canRetry)
  }

  // ------------------------------------------------------------------ backfill

  /**
   * The reason backfill exists: adding a column does not fill it.
   *
   * The ordinary sync looks back a fortnight, so an activity older than that keeps a null in every
   * column added after it was first stored. This walks the whole history instead.
   */
  @Test
  fun `backfill walks back a year at a time and stores what it finds`() = runTest(dispatcher) {
    // Every year answers with one activity, so the walk only ends at the cap.
    var request = 0
    server.removeContext("/")
    server.createContext("/") { exchange: HttpExchange ->
      val bytes = oneRunBodyWrapped("i${request++}", "2026-08-15T06:00:00Z").toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }

    val result = repository.backfill(today = TO, zone = zone, maxYears = 3)

    assertEquals(3, result.yearsScanned)
    assertEquals(3, result.activities)
    assertNull(result.failure)
    assertEquals(3, db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE).size)
  }

  /**
   * One empty year is a season off; two is the end of the history. Stopping on the first would
   * truncate an athlete who took a winter away from running.
   */
  @Test
  fun `backfill stops after two consecutive empty years, not one`() = runTest(dispatcher) {
    // Year 0 has one, year 1 is empty, year 2 has one, years 3 and 4 are empty.
    val years = listOf(oneRun("i1"), "[]", oneRun("i2"), "[]", "[]", oneRun("i3"))
    var request = 0
    server.removeContext("/")
    server.createContext("/") { exchange: HttpExchange ->
      val bytes = years.getOrElse(request++) { "[]" }.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }

    val result = repository.backfill(today = TO, zone = zone, maxYears = 10)

    // Scanned years 0..4 and stopped at the second consecutive empty, never reaching year 5.
    assertEquals(5, result.yearsScanned)
    assertEquals(2, result.activities)
  }

  /** The whole point of keying rows on the service's id: a backfill over a synced range is safe. */
  @Test
  fun `backfill does not duplicate what the sync already stored`() = runTest(dispatcher) {
    body = oneRun(id = "i1")
    repository.sync(FROM, TO, zone)

    repository.backfill(today = TO, zone = zone, maxYears = 2)

    assertEquals(1, db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE).size)
  }

  /**
   * A top-up, not a transaction: a year that arrived before the failure is worth keeping, and the
   * result says how far it got rather than pretending nothing happened.
   */
  @Test
  fun `a failure part-way keeps what was already stored and reports it`() = runTest(dispatcher) {
    var request = 0
    server.removeContext("/")
    server.createContext("/") { exchange: HttpExchange ->
      if (request++ == 0) {
        val bytes = oneRun("i1").toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      } else {
        exchange.sendResponseHeaders(503, 0)
        exchange.responseBody.use { }
      }
    }

    val result = repository.backfill(today = TO, zone = zone, maxYears = 5)

    assertEquals(1, result.activities)
    assertTrue(result.failure.orEmpty(), result.failure != null)
    assertEquals(1, db.intervalsDao().getActivitiesBetween(0, Long.MAX_VALUE).size)
  }

  /** An athlete with no history at all gets two requests and a clean zero, not an error. */
  @Test
  fun `an empty account backfills to nothing without failing`() = runTest(dispatcher) {
    body = "[]"

    val result = repository.backfill(today = TO, zone = zone, maxYears = 10)

    assertEquals(0, result.activities)
    assertEquals(2, result.yearsScanned)
    assertNull(result.failure)
  }

  // ------------------------------------------------------------------ matching

  @Test
  fun `a run is tied to the running session nearest it that day`() = runTest(dispatcher) {
    body = oneRunBodyWrapped("i1", "2026-08-15T06:12:03Z")
    repository.sync(FROM, TO, zone)

    repository.matchActivities(
      sessions =
        listOf(
          PlannedSession(
            id = "session-run",
            scheduledAtUtc = Instant.parse("2026-08-15T06:00:00Z").toEpochMilli(),
            type = WorkoutType.RUNNING,
          )
        ),
      fromUtc = 0,
      toUtc = Long.MAX_VALUE,
    )

    val metrics = repository.observeMatchedRunMetrics().first()
    assertEquals(setOf("session-run"), metrics.keys)
    assertEquals("i1", metrics.getValue("session-run").activityId)
  }

  /**
   * The rule inherited from the Oura matcher: a walk is not a run. Without it, the nearest
   * activity to a morning session was almost always a stroll.
   */
  @Test
  fun `a walk does not claim a running session`() = runTest(dispatcher) {
    body = """[${oneRunBody("i1", "2026-08-15T06:12:03Z", type = "Walk")}]"""
    repository.sync(FROM, TO, zone)

    repository.matchActivities(
      sessions =
        listOf(
          PlannedSession(
            id = "session-run",
            scheduledAtUtc = Instant.parse("2026-08-15T06:00:00Z").toEpochMilli(),
            type = WorkoutType.RUNNING,
          )
        ),
      fromUtc = 0,
      toUtc = Long.MAX_VALUE,
    )

    assertTrue(repository.observeMatchedRunMetrics().first().isEmpty())
  }

  /** Matching runs after every sync, so an unchanged decision must rewrite the same value. */
  @Test
  fun `matching twice is the same as matching once`() = runTest(dispatcher) {
    body = oneRunBodyWrapped("i1", "2026-08-15T06:12:03Z")
    repository.sync(FROM, TO, zone)
    val sessions =
      listOf(
        PlannedSession(
          id = "session-run",
          scheduledAtUtc = Instant.parse("2026-08-15T06:00:00Z").toEpochMilli(),
          type = WorkoutType.RUNNING,
        )
      )

    repository.matchActivities(sessions, 0, Long.MAX_VALUE)
    repository.matchActivities(sessions, 0, Long.MAX_VALUE)

    assertEquals(1, repository.observeMatchedRunMetrics().first().size)
  }

  /** A session moved away from its activity must lose the activity it used to claim. */
  @Test
  fun `an activity out of every session's reach is unmatched again`() = runTest(dispatcher) {
    body = oneRunBodyWrapped("i1", "2026-08-15T06:12:03Z")
    repository.sync(FROM, TO, zone)
    repository.matchActivities(
      listOf(
        PlannedSession(
          "session-run",
          Instant.parse("2026-08-15T06:00:00Z").toEpochMilli(),
          WorkoutType.RUNNING,
        )
      ),
      0,
      Long.MAX_VALUE,
    )

    // The session moves a week out — far past the matcher's twelve-hour reach.
    repository.matchActivities(
      listOf(
        PlannedSession(
          "session-run",
          Instant.parse("2026-08-22T06:00:00Z").toEpochMilli(),
          WorkoutType.RUNNING,
        )
      ),
      0,
      Long.MAX_VALUE,
    )

    assertTrue(repository.observeMatchedRunMetrics().first().isEmpty())
  }

  @Test
  fun `nothing matched means no metrics rather than an error`() = runTest(dispatcher) {
    body = oneRunBodyWrapped("i1", "2026-08-15T06:12:03Z")
    repository.sync(FROM, TO, zone)

    repository.matchActivities(emptyList(), 0, Long.MAX_VALUE)

    assertTrue(repository.observeMatchedRunMetrics().first().isEmpty())
  }

  @Test
  fun `pace and training load survive the round trip to the screen`() = runTest(dispatcher) {
    body = oneRunBodyWrapped("i1", "2026-08-15T06:12:03Z")
    repository.sync(FROM, TO, zone)
    repository.matchActivities(
      listOf(
        PlannedSession(
          "session-run",
          Instant.parse("2026-08-15T06:00:00Z").toEpochMilli(),
          WorkoutType.RUNNING,
        )
      ),
      0,
      Long.MAX_VALUE,
    )

    val metrics = repository.observeMatchedRunMetrics().first().getValue("session-run")

    assertEquals(6.2, metrics.distanceKm!!, 0.001)
    // 3226 s over 6.2 km is 367.7 s/km, rounded — the fixture carries no average_speed, so the
    // moving time leads.
    assertEquals("6:08 /km", metrics.paceText)
    assertEquals(78, metrics.trainingLoad)
    assertEquals(540, metrics.calories)
  }

  private companion object {
    val FROM: LocalDate = LocalDate.of(2026, 8, 1)
    val TO: LocalDate = LocalDate.of(2026, 8, 15)
    const val FETCHED_AT = 1_755_000_000_000L

    fun oneRunBody(
      id: String,
      start: String = "2026-08-15T06:12:03Z",
      type: String = "Run",
      movingTime: Int = 2280,
    ) =
      """{"id":"$id","name":"Aamulenkki","type":"$type","start_date":"$start",
         "moving_time":$movingTime,"elapsed_time":2400,"distance":6200.0,
         "average_heartrate":148,"max_heartrate":171,"total_elevation_gain":42.0,
         "calories":540,"icu_training_load":78,"source":"SUUNTO"}"""

    fun oneRun(id: String, movingTime: Int = 2280) = "[${oneRunBody(id, movingTime = movingTime)}]"

    fun oneRunBodyWrapped(id: String, start: String) = "[${oneRunBody(id, start)}]"
  }
}
