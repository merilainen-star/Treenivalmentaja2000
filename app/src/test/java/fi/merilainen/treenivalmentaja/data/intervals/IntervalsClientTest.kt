package fi.merilainen.treenivalmentaja.data.intervals

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The client, against a local HTTP server — the same way the Oura client and the guide providers
 * are tested, and for the same reason: no real API key is needed to prove how this behaves.
 *
 * What is *not* proven here is that intervals.icu's own answers match these shapes. The fixtures
 * are built from the vendored specification (`docs/api/intervals-icu-openapi.json`), so a service
 * that changed shape would pass this suite and fail on the phone. That limit is worth stating
 * rather than glossing.
 */
class IntervalsClientTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = "[]"
  private var retryAfter: String? = null

  /** What the last request carried, so the test can assert on it. */
  private var lastPath: String? = null
  private var lastQuery: String? = null
  private var lastAuthorization: String? = null

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/athlete/0/activities") { exchange: HttpExchange ->
      lastPath = exchange.requestURI.path
      lastQuery = exchange.requestURI.query
      lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
      retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
  }

  @After
  fun stop() {
    server.stop(0)
  }

  private fun client(key: String? = "test-key") =
    IntervalsClient(apiKeys = { key }, baseUrl = "http://127.0.0.1:${server.address.port}")

  // ------------------------------------------------------------------ the request

  /**
   * Basic auth with the literal username `API_KEY`, which the specification states in as many
   * words. Getting this wrong is a 401 that looks exactly like a mistyped key.
   */
  @Test
  fun `the request authenticates as API_KEY with the key as the password`() = runTest {
    client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))

    val encoded = lastAuthorization!!.removePrefix("Basic ")
    assertEquals("API_KEY:test-key", String(Base64.getDecoder().decode(encoded)))
  }

  @Test
  fun `the date range travels as oldest and newest`() = runTest {
    client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))

    assertEquals("/api/v1/athlete/0/activities", lastPath)
    assertTrue(lastQuery!!, lastQuery!!.contains("oldest=2026-08-01"))
    assertTrue(lastQuery!!, lastQuery!!.contains("newest=2026-08-15"))
  }

  /**
   * The `Activity` schema declares 183 properties. Naming the dozen this app reads is not a
   * micro-optimisation — without it every property of every activity in the range is sent.
   */
  @Test
  fun `only the fields the app reads are requested`() = runTest {
    client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))

    val query = lastQuery!!
    assertTrue(query, query.contains("fields="))
    assertTrue(query, query.contains("icu_training_load"))
    assertTrue(query, query.contains("moving_time"))
  }

  // ------------------------------------------------------------------ the response

  @Test
  fun `activities are parsed from the array the service returns`() = runTest {
    body =
      """
      [{"id":"i1","name":"Aamulenkki","type":"Run","start_date":"2026-08-15T06:12:03Z",
        "moving_time":2280,"elapsed_time":2400,"distance":6200.0,"average_heartrate":148,
        "max_heartrate":171,"calories":540,"icu_training_load":78,"source":"SUUNTO"}]
      """
        .trimIndent()

    val activities = client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))

    val activity = activities.single()
    assertEquals("i1", activity.id)
    assertEquals(2280, activity.movingTime)
    assertEquals(78, activity.icuTrainingLoad)
    assertEquals("SUUNTO", activity.source)
  }

  /**
   * The `fields` parameter is documented to drop nulls, so a response legitimately omits most of
   * what the DTO declares. Every field being nullable is what keeps that from being a crash.
   */
  @Test
  fun `an activity carrying only a few fields still parses`() = runTest {
    body = """[{"id":"i1","type":"Run","start_date":"2026-08-15T06:12:03Z","moving_time":600}]"""

    val activity = client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)).single()

    assertEquals("i1", activity.id)
    assertNull(activity.averageHeartrate)
    assertNull(activity.icuTrainingLoad)
  }

  @Test
  fun `an empty range is an empty list, not a failure`() = runTest {
    body = "[]"

    assertTrue(client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)).isEmpty())
  }

  // ------------------------------------------------------------------ failures

  /** Measured against the real service: it answers 401 for both no key and a wrong one. */
  @Test
  fun `a rejected key is an auth failure that cannot be retried`() = runTest {
    status = 401

    val thrown =
      runCatching { client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)) }
        .exceptionOrNull()

    assertTrue(thrown.toString(), thrown is IntervalsAuthException)
    assertEquals(false, (thrown as IntervalsException).canRetry)
  }

  /** The service's own number, not a guess of ours dressed up as its instruction. */
  @Test
  fun `a rate limit carries the service's Retry-After`() = runTest {
    status = 429
    retryAfter = "120"

    val thrown =
      runCatching { client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)) }
        .exceptionOrNull()

    assertTrue(thrown.toString(), thrown is IntervalsRateLimitException)
    assertEquals(120L, (thrown as IntervalsRateLimitException).retryAfterSeconds)
    assertTrue(thrown.canRetry)
  }

  /** No header is `null` rather than a number the app invented and attributed to the service. */
  @Test
  fun `a rate limit without a Retry-After header says so`() = runTest {
    status = 429
    retryAfter = null

    val thrown =
      runCatching { client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)) }
        .exceptionOrNull()

    assertNull((thrown as IntervalsRateLimitException).retryAfterSeconds)
  }

  @Test
  fun `a nonsense Retry-After is ignored rather than parsed into zero`() = runTest {
    status = 429
    retryAfter = "soon"

    val thrown =
      runCatching { client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)) }
        .exceptionOrNull()

    assertNull((thrown as IntervalsRateLimitException).retryAfterSeconds)
  }

  @Test
  fun `a server error is retryable`() = runTest {
    status = 503

    val thrown =
      runCatching { client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)) }
        .exceptionOrNull()

    assertTrue(thrown.toString(), thrown is IntervalsUnavailableException)
    assertTrue((thrown as IntervalsException).canRetry)
  }

  /** A proxy's HTML error page with a 200 on it. */
  @Test
  fun `a body that is not JSON is reported rather than crashing`() = runTest {
    body = "<html>maintenance</html>"

    val thrown =
      runCatching { client().activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15)) }
        .exceptionOrNull()

    assertTrue(thrown.toString(), thrown is IntervalsUnavailableException)
  }

  @Test
  fun `no key at all is a configuration problem, not a network one`() = runTest {
    val thrown =
      runCatching {
          client(key = null).activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))
        }
        .exceptionOrNull()

    assertTrue(thrown.toString(), thrown is IntervalsNotConfiguredException)
  }

  @Test
  fun `a blank key is treated as no key`() = runTest {
    val thrown =
      runCatching {
          client(key = "   ").activities(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))
        }
        .exceptionOrNull()

    assertTrue(thrown.toString(), thrown is IntervalsNotConfiguredException)
  }

  // ------------------------------------------------------------------ the connection test

  /** It asks the endpoint the sync actually uses, so a pass means the sync will work. */
  @Test
  fun `testing a key calls the activities endpoint with a limit`() = runTest {
    body = """[{"id":"i1"}]"""

    assertEquals(1, client().testKey())
    assertEquals("/api/v1/athlete/0/activities", lastPath)
    assertTrue(lastQuery!!, lastQuery!!.contains("limit=1"))
  }

  /**
   * Zero activities is a **success**: the key authenticated and the account had nothing in the
   * window. Calling it a failure would send someone hunting for a broken key that is fine.
   */
  @Test
  fun `a key that works but finds nothing is still a working key`() = runTest {
    body = "[]"

    assertEquals(0, client().testKey())
  }
}
