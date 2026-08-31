package fi.merilainen.treenivalmentaja.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.oura.OuraClient
import fi.merilainen.treenivalmentaja.data.oura.OuraTokenSource
import java.net.InetSocketAddress
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
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
 * The whole data path, from an HTTP response to a row a screen can observe.
 *
 * A real client against a local server and a real in-memory Room database, because the interesting
 * failures live between the parts rather than inside them — a day fetched from three collections
 * becoming one row, a failed sync leaving what was already stored alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OuraRepositoryTest {

  private val dispatcher = StandardTestDispatcher()

  private lateinit var db: AppDatabase
  private lateinit var server: HttpServer
  private lateinit var repository: OuraRepository

  /** Path -> (status, body). Replaced per test. */
  private var routes: Map<String, Pair<Int, String>> = emptyMap()

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
      val (status, body) = routes[exchange.requestURI.path] ?: (200 to EMPTY)
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    repository =
      OuraRepository(
        client =
          OuraClient(
            tokens = OuraTokenSource { "test-token" },
            baseUrl = "http://127.0.0.1:${server.address.port}",
          ),
        dao = db.ouraDao(),
        clock = { FETCHED_AT },
      )
  }

  @After
  fun tearDown() {
    server.stop(0)
    db.close()
  }

  private fun scores(day: String, score: String) =
    """{"data":[{"id":"$day","day":"$day","score":$score,"timestamp":"${day}T00:00:00+03:00"}],"next_token":null}"""

  private fun readinessWithContributors(day: String, score: String) =
    """{"data":[{"id":"$day","day":"$day","score":$score,"timestamp":"${day}T00:00:00+03:00",
      "contributors":{"activity_balance":78,"body_temperature":96,"hrv_balance":85,
      "previous_day_activity":80,"previous_night":92,"recovery_index":88,"resting_heart_rate":90,
      "sleep_balance":87,"sleep_regularity":83}}],"next_token":null}"""

  private fun activityWithContributors(day: String, score: String, recoveryTime: String) =
    """{"data":[{"id":"$day","day":"$day","score":$score,"timestamp":"${day}T00:00:00+03:00",
      "contributors":{"recovery_time":$recoveryTime}}],"next_token":null}"""

  // ------------------------------------------------------------------ syncing

  @Test
  fun `three collections become one observable row`() = runTest(dispatcher) {
    routes =
      mapOf(
        READINESS to (200 to scores(DAY, "66")),
        SLEEP to (200 to scores(DAY, "80")),
        ACTIVITY to (200 to scores(DAY, "91")),
        WORKOUT to (200 to EMPTY),
      )

    val result = repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    assertEquals(OuraSyncResult.Success(days = 1, workouts = 0), result)
    val stored = repository.observeDay(LocalDate.parse(DAY)).first()!!
    assertEquals(66, stored.readiness)
    assertEquals(80, stored.sleep)
    assertEquals(91, stored.activity)
    assertEquals(FETCHED_AT, stored.fetchedAtUtc)
  }

  /**
   * The contributor breakdown behind `readiness` and `activity` survives the whole path: an HTTP
   * body, through the entity columns added for it (ADR-014), to the domain object a screen observes.
   */
  @Test
  fun `contributors reach the observed row`() = runTest(dispatcher) {
    routes =
      mapOf(
        READINESS to (200 to readinessWithContributors(DAY, "91")),
        SLEEP to (200 to EMPTY),
        ACTIVITY to (200 to activityWithContributors(DAY, "80", "62")),
        WORKOUT to (200 to EMPTY),
      )

    repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    val stored = repository.observeDay(LocalDate.parse(DAY)).first()!!
    assertEquals(62, stored.activityRecoveryTime)
    val contributors = stored.readinessContributors!!
    assertEquals(85, contributors.hrvBalance)
    assertEquals(90, contributors.restingHeartRate)
    assertEquals(92, contributors.previousNight)
  }

  /**
   * The day the ring was not worn. Oura answers with a document and no score, and it has to survive
   * as a row that exists with nothing in it — the card says something different about that than
   * about a day never fetched.
   */
  @Test
  fun `a day without a score is stored as a day without a score`() = runTest(dispatcher) {
    routes =
      mapOf(
        READINESS to (200 to scores(DAY, "null")),
        SLEEP to (200 to EMPTY),
        ACTIVITY to (200 to EMPTY),
        WORKOUT to (200 to EMPTY),
      )

    repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    val stored = repository.observeDay(LocalDate.parse(DAY)).first()!!
    assertNull(stored.readiness)
    assertTrue(stored.isEmpty)
  }

  @Test
  fun `a day never fetched observes as nothing at all`() = runTest(dispatcher) {
    assertNull(repository.observeDay(LocalDate.parse(DAY)).first())
  }

  @Test
  fun `workouts are stored with their times`() = runTest(dispatcher) {
    routes =
      mapOf(
        READINESS to (200 to EMPTY),
        SLEEP to (200 to EMPTY),
        ACTIVITY to (200 to EMPTY),
        WORKOUT to
          (200 to
            """{"data":[{"id":"w1","activity":"running","day":"$DAY","start_datetime":"${DAY}T18:00:00+03:00","end_datetime":"${DAY}T18:40:00+03:00","calories":431.0,"intensity":"moderate","source":"confirmed"}],"next_token":null}"""),
      )

    val result = repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    assertEquals(OuraSyncResult.Success(days = 0, workouts = 1), result)
    val rows = db.ouraDao().getWorkoutsBetween(0, Long.MAX_VALUE)
    assertEquals(1, rows.size)
    assertEquals("running", rows.single().activityType)
  }

  /** Oura revises a day once the night has been processed; a re-sync must overwrite, not duplicate. */
  @Test
  fun `re-syncing a day replaces it rather than adding a second one`() = runTest(dispatcher) {
    routes = mapOf(READINESS to (200 to scores(DAY, "66")))
    repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    routes = mapOf(READINESS to (200 to scores(DAY, "72")))
    repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    assertEquals(72, repository.observeDay(LocalDate.parse(DAY)).first()!!.readiness)
  }

  // ------------------------------------------------------------------ diagnostics

  /**
   * The case this whole feature was built for: Oura answers, and has no workouts. Telling that
   * apart from "we never asked" and from "the request failed" was impossible from the screen, and
   * that ambiguity is what made a real missing session undebuggable.
   */
  @Test
  fun `diagnostics report an empty workout collection as empty, not as a failure`() =
    runTest(dispatcher) {
      routes =
        mapOf(
          READINESS to (200 to scores(DAY, "66")),
          SLEEP to (200 to EMPTY),
          ACTIVITY to (200 to EMPTY),
          WORKOUT to (200 to EMPTY),
        )

      val result = repository.diagnose(LocalDate.parse(DAY), LocalDate.parse(DAY))

      assertEquals(0, result.workoutCount)
      assertEquals(1, result.readinessDays)
      assertTrue(result.failures.toString(), result.failures.isEmpty())
    }

  @Test
  fun `diagnostics list each workout Oura returned`() = runTest(dispatcher) {
    routes = mapOf(WORKOUT to (200 to """{"data":[{"id":"w1","activity":"strength_training","day":"$DAY","start_datetime":"${DAY}T07:38:00+03:00","end_datetime":"${DAY}T08:08:00+03:00","calories":135.0,"intensity":"moderate","source":"autodetected"}],"next_token":null}"""))

    val result = repository.diagnose(LocalDate.parse(DAY), LocalDate.parse(DAY))

    assertEquals(1, result.workoutCount)
    val line = result.workouts.single()
    assertTrue(line, line.contains("strength_training"))
    assertTrue(line, line.contains("07:38"))
    assertTrue(line, line.contains("135 kcal"))
    // Whether Oura noticed the session or someone typed it in is exactly the kind of thing worth
    // seeing when a workout is missing.
    assertTrue(line, line.contains("autodetected"))
  }

  /** One collection failing must not hide what the others answered. */
  @Test
  fun `a failing collection is reported without silencing the rest`() = runTest(dispatcher) {
    routes =
      mapOf(
        READINESS to (200 to scores(DAY, "66")),
        WORKOUT to (401 to """{"detail":"nope"}"""),
      )

    val result = repository.diagnose(LocalDate.parse(DAY), LocalDate.parse(DAY))

    assertEquals(1, result.readinessDays)
    assertEquals(0, result.workoutCount)
    assertTrue(result.failures.toString(), result.failures.any { it.startsWith("Treenit") })
  }

  /** Diagnostics answer a question; they must not change the answer. */
  @Test
  fun `diagnostics store nothing`() = runTest(dispatcher) {
    routes = mapOf(READINESS to (200 to scores(DAY, "66")))

    repository.diagnose(LocalDate.parse(DAY), LocalDate.parse(DAY))

    assertNull(repository.observeDay(LocalDate.parse(DAY)).first())
  }

  // ------------------------------------------------------------------ when it fails

  /**
   * Room is the source of truth, so a failed fetch must leave it exactly as it was. Anything else
   * would mean an offline morning could erase a reading the phone already had.
   */
  @Test
  fun `a failed sync changes nothing that was already stored`() = runTest(dispatcher) {
    routes = mapOf(READINESS to (200 to scores(DAY, "66")))
    repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    routes = mapOf(READINESS to (503 to "unavailable"))
    val result = repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    assertTrue(result is OuraSyncResult.Failure)
    assertEquals(66, repository.observeDay(LocalDate.parse(DAY)).first()!!.readiness)
  }

  /** A failure is returned, never thrown: syncing happens unasked and must not crash a screen. */
  @Test
  fun `a rate limit comes back as a retryable failure in Finnish`() = runTest(dispatcher) {
    routes = mapOf(READINESS to (429 to "slow down"))

    val result =
      repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))
        as OuraSyncResult.Failure

    assertTrue(result.message, result.message.contains("liian tiheästi"))
    assertTrue(result.canRetry)
  }

  /** An expired Oura subscription is a state to show, and waiting will not change it. */
  @Test
  fun `an ended subscription is not retryable`() = runTest(dispatcher) {
    routes = mapOf(READINESS to (403 to """{"detail":"expired"}"""))

    val result =
      repository.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))
        as OuraSyncResult.Failure

    assertEquals(false, result.canRetry)
    assertTrue(result.message, result.message.contains("tilaus"))
  }

  @Test
  fun `a sync with no token at all fails quietly rather than throwing`() = runTest(dispatcher) {
    val disconnected =
      OuraRepository(
        client =
          OuraClient(
            tokens = OuraTokenSource { null },
            baseUrl = "http://127.0.0.1:${server.address.port}",
          ),
        dao = db.ouraDao(),
      )

    val result = disconnected.sync(from = LocalDate.parse(DAY), to = LocalDate.parse(DAY))

    assertTrue(result is OuraSyncResult.Failure)
  }

  private companion object {
    const val DAY = "2026-08-09"
    const val FETCHED_AT = 1_754_800_000_000L

    const val READINESS = "/v2/usercollection/daily_readiness"
    const val SLEEP = "/v2/usercollection/daily_sleep"
    const val ACTIVITY = "/v2/usercollection/daily_activity"
    const val WORKOUT = "/v2/usercollection/workout"

    const val EMPTY = """{"data":[],"next_token":null}"""
  }
}
