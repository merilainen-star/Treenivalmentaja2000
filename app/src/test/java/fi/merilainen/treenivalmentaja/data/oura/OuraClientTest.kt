package fi.merilainen.treenivalmentaja.data.oura

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The client against a throwaway local server.
 *
 * **The fixtures under `src/test/resources/oura/` are derived from the vendored specification, not
 * captured from the live service.** That is a real difference from the guide fixtures next door,
 * which are recorded responses. `docs/api/oura-openapi-1.37.json` contains no response examples at
 * all — checked, it has zero `example` keys — so every field name, every optional marker and every
 * enum value in them was read out of the schemas instead.
 *
 * The client does now run against a real account, and the readiness and sleep scores it shows have
 * been checked by hand against Oura's own app and matched. What these tests prove remains narrower
 * than that: that the client honours the specification.
 *
 * `com.sun.net.httpserver` rather than MockWebServer, matching `ExerciseDbProviderTest`: the JDK
 * already ships a server, and this needs a handful of handlers.
 */
class OuraClientTest {

  private lateinit var server: HttpServer

  /** Path -> (status, body). Replaced per test. */
  private var routes: Map<String, Pair<Int, String>> = emptyMap()

  /** Every request the server saw, in order. What proves a header or a parameter was sent. */
  private val received = mutableListOf<Received>()

  private data class Received(val path: String, val query: String?, val authorization: String?)

  private val token = OuraTokenSource { "test-access-token" }

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange: HttpExchange ->
      received +=
        Received(
          path = exchange.requestURI.path,
          query = exchange.requestURI.query,
          authorization = exchange.requestHeaders.getFirst("Authorization"),
        )
      val (status, body) = routes[exchange.requestURI.path] ?: (404 to "not found")
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

  private fun client(
    tokens: OuraTokenSource = token,
    useSandbox: Boolean = false,
  ): OuraClient =
    OuraClient(
      tokens = tokens,
      baseUrl = "http://127.0.0.1:${server.address.port}",
      useSandbox = useSandbox,
    )

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/oura/$name")) { "missing fixture $name" }
      .bufferedReader()
      .use { it.readText() }

  private fun readinessRoute(body: String) =
    mapOf(READINESS_PATH to (200 to body))

  /**
   * Answers successive requests with successive bodies, so a fixture carrying a `next_token` is
   * followed by the page that ends the run rather than by itself. Repeating one page forever is a
   * different test — see the page cap below.
   */
  private fun servePages(vararg bodies: String) {
    var call = 0
    server.removeContext("/")
    server.createContext("/") { exchange ->
      received +=
        Received(
          path = exchange.requestURI.path,
          query = exchange.requestURI.query,
          authorization = exchange.requestHeaders.getFirst("Authorization"),
        )
      val bytes = bodies[minOf(call++, bodies.lastIndex)].toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
  }

  // ------------------------------------------------------------------ the request

  @Test
  fun `the access token is sent as a bearer header`() = runTest {
    routes = readinessRoute(fixture("daily_readiness_page2.json"))

    client().readiness(FROM, TO)

    assertEquals("Bearer test-access-token", received.single().authorization)
  }

  @Test
  fun `the range is sent as start_date and end_date`() = runTest {
    routes = readinessRoute(fixture("daily_readiness_page2.json"))

    client().readiness(FROM, TO)

    val query = received.single().query!!
    assertTrue(query, query.contains("start_date=2026-08-07"))
    assertTrue(query, query.contains("end_date=2026-08-09"))
  }

  /** The sandbox is a different path on the same host, and it still carries the token. */
  @Test
  fun `the sandbox is a path, not a different service`() = runTest {
    routes = mapOf("/v2/sandbox/usercollection/daily_readiness" to (200 to fixture("daily_readiness_page2.json")))

    client(useSandbox = true).readiness(FROM, TO)

    assertEquals("/v2/sandbox/usercollection/daily_readiness", received.single().path)
    assertEquals("Bearer test-access-token", received.single().authorization)
  }

  /** Each collection is the same request against a different path. */
  @Test
  fun `every collection asks its own path`() = runTest {
    routes =
      mapOf(
        READINESS_PATH to (200 to EMPTY_PAGE),
        "/v2/usercollection/daily_sleep" to (200 to EMPTY_PAGE),
        "/v2/usercollection/daily_activity" to (200 to EMPTY_PAGE),
        "/v2/usercollection/workout" to (200 to EMPTY_PAGE),
      )

    client().readiness(FROM, TO)
    client().sleep(FROM, TO)
    client().activity(FROM, TO)
    client().workouts(FROM, TO)

    assertEquals(
      listOf(
        "/v2/usercollection/daily_readiness",
        "/v2/usercollection/daily_sleep",
        "/v2/usercollection/daily_activity",
        "/v2/usercollection/workout",
      ),
      received.map { it.path },
    )
  }

  /** Without a token there is nothing to ask with, and the request is never made. */
  @Test
  fun `an unconnected Oura fails before anything is sent`() = runTest {
    routes = readinessRoute(fixture("daily_readiness_page2.json"))

    val failure = runCatching { client(tokens = { null }).readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraNotConnectedException)
    assertEquals(emptyList<Received>(), received)
  }

  @Test
  fun `a blank token is no token`() = runTest {
    val failure = runCatching { client(tokens = { "   " }).readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraNotConnectedException)
    assertEquals(emptyList<Received>(), received)
  }

  // ------------------------------------------------------------------ paging

  @Test
  fun `pages are followed until next_token stops`() = runTest {
    servePages(fixture("daily_readiness_page1.json"), fixture("daily_readiness_page2.json"))

    val days = client().readiness(FROM, TO)

    assertEquals(listOf("2026-08-07", "2026-08-08", "2026-08-09"), days.map { it.day })
    assertEquals(2, received.size)
  }

  @Test
  fun `the second page carries the token the first one returned`() = runTest {
    servePages(fixture("daily_readiness_page1.json"), fixture("daily_readiness_page2.json"))

    client().readiness(FROM, TO)

    assertFalse(received.first().query!!.contains("next_token"))
    // Decoded before comparing: the token is base64 and ends in padding, and whether `=` survives
    // as itself or as `%3D` is the HTTP client's business, not this test's.
    val query = URLDecoder.decode(received[1].query!!, "UTF-8")
    assertTrue(query, query.contains("next_token=MjAyNi0wOC0wOFQwMDowMDowMA=="))
  }

  /**
   * A service that never stops handing out tokens would otherwise loop until the process died.
   * Giving up loudly beats returning part of an answer as though it were all of it.
   */
  @Test
  fun `an endless stream of pages ends in an error, not a loop`() = runTest {
    server.removeContext("/")
    server.createContext("/") { exchange ->
      received += Received(exchange.requestURI.path, exchange.requestURI.query, null)
      val bytes = ENDLESS_PAGE.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraUnavailableException)
    assertTrue(failure!!.message!!, failure.message!!.contains("${OuraClient.MAX_PAGES}"))
    assertEquals(OuraClient.MAX_PAGES, received.size)
  }

  // ------------------------------------------------------------------ what comes back

  /**
   * The whole design constraint of the recovery card, held in place here: a day the ring was not
   * worn is a document with **no score**, not a missing document and certainly not a zero.
   */
  @Test
  fun `a day without a score is kept as a day without a score`() = runTest {
    servePages(fixture("daily_readiness_page1.json"), fixture("daily_readiness_page2.json"))

    val days = client().readiness(FROM, TO)

    val unworn = days.single { it.day == "2026-08-08" }
    assertNull(unworn.score)
    assertEquals(66, days.single { it.day == "2026-08-07" }.score)
  }

  @Test
  fun `a workout payload maps onto its fields`() = runTest {
    routes = mapOf("/v2/usercollection/workout" to (200 to fixture("workout.json")))

    val workouts = client().workouts(FROM, TO)

    assertEquals(2, workouts.size)
    val run = workouts.first()
    assertEquals("running", run.activity)
    assertEquals(431.0, run.calories!!, 0.001)
    assertEquals("2026-08-08T18:00:00.000000+03:00", run.startDatetime)
    assertEquals("confirmed", run.source)
    assertNull(workouts[1].calories)
  }

  // ------------------------------------------------------------------ heart rate

  /**
   * The one collection that takes instants rather than dates. A day-shaped request here would
   * return a day of samples for a forty-minute workout.
   */
  @Test
  fun `heart rate is asked for by instant, not by date`() = runTest {
    routes = mapOf("/v2/usercollection/heartrate" to (200 to HEART_RATE_PAGE))

    client()
      .heartRate(
        from = Instant.parse("2026-08-08T15:00:00Z"),
        to = Instant.parse("2026-08-08T15:40:00Z"),
      )

    val query = URLDecoder.decode(received.single().query!!, "UTF-8")
    assertTrue(query, query.contains("start_datetime=2026-08-08T15:00:00Z"))
    assertTrue(query, query.contains("end_datetime=2026-08-08T15:40:00Z"))
    assertFalse(query, query.contains("start_date="))
  }

  @Test
  fun `heart rate samples come back with their beats`() = runTest {
    routes = mapOf("/v2/usercollection/heartrate" to (200 to HEART_RATE_PAGE))

    val samples =
      client().heartRate(Instant.parse("2026-08-08T15:00:00Z"), Instant.parse("2026-08-08T15:40:00Z"))

    assertEquals(2, samples.size)
    assertEquals(140, samples.first().bpm)
    assertEquals("workout", samples.first().source)
  }

  /**
   * A connection granted before the `heartrate` scope existed gets a `401` here and nothing else.
   * It has to surface as an auth failure so the caller can carry on without heart rate rather than
   * treat the whole sync as broken.
   */
  @Test
  fun `heart rate without the scope is an auth failure`() = runTest {
    routes = mapOf("/v2/usercollection/heartrate" to (401 to """{"detail":"missing scope"}"""))

    val failure =
      runCatching {
          client().heartRate(Instant.parse("2026-08-08T15:00:00Z"), Instant.parse("2026-08-08T15:40:00Z"))
        }
        .exceptionOrNull()

    assertTrue(failure is OuraAuthException)
  }

  // ------------------------------------------------------------------ failures

  @Test
  fun `401 is an expired connection and is not retried here`() = runTest {
    routes = mapOf(READINESS_PATH to (401 to """{"detail":"expired"}"""))

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraAuthException)
    assertFalse((failure as OuraException).canRetry)
  }

  /** 403 is the odd one out: the subscription ended. Asking again will not bring the data back. */
  @Test
  fun `403 reports an ended subscription rather than a failure to retry`() = runTest {
    routes = mapOf(READINESS_PATH to (403 to """{"detail":"subscription expired"}"""))

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraSubscriptionExpiredException)
    assertFalse((failure as OuraException).canRetry)
    assertTrue(failure.message!!, failure.message!!.contains("tilaus"))
  }

  @Test
  fun `429 says so in Finnish and may be retried`() = runTest {
    routes = mapOf(READINESS_PATH to (429 to "rate limited"))

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    val limited = failure as OuraRateLimitException
    assertTrue(limited.message!!, limited.message!!.contains("liian tiheästi"))
    assertTrue(limited.canRetry)
  }

  /** A rejected request is this app's bug. Repeating it produces the same rejection. */
  @Test
  fun `422 is not retryable`() = runTest {
    routes = mapOf(READINESS_PATH to (422 to """{"detail":[]}"""))

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraRequestException)
    assertFalse((failure as OuraException).canRetry)
  }

  @Test
  fun `a 5xx is reported by its status`() = runTest {
    routes = mapOf(READINESS_PATH to (503 to "<html>unavailable</html>"))

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    assertTrue((failure as OuraUnavailableException).message!!.contains("503"))
  }

  /** The status is read before the body is trusted; a failing service owes no JSON. */
  @Test
  fun `a 200 that is not JSON is a readable failure, not a crash`() = runTest {
    routes = mapOf(READINESS_PATH to (200 to "<html>hello</html>"))

    val failure = runCatching { client().readiness(FROM, TO) }.exceptionOrNull()

    assertTrue(failure is OuraUnavailableException)
    assertEquals(OuraClient.UNREADABLE, failure!!.message)
  }

  @Test
  fun `an unreachable host is a network failure`() = runTest {
    val offline = OuraClient(tokens = token, baseUrl = "http://127.0.0.1:1")

    val failure = runCatching { offline.readiness(FROM, TO) }.exceptionOrNull()

    val unavailable = failure as OuraUnavailableException
    assertEquals(OuraClient.OFFLINE, unavailable.message)
    assertTrue(unavailable.canRetry)
  }

  private companion object {
    val FROM: LocalDate = LocalDate.of(2026, 8, 7)
    val TO: LocalDate = LocalDate.of(2026, 8, 9)

    const val READINESS_PATH = "/v2/usercollection/daily_readiness"

    const val EMPTY_PAGE = """{"data":[],"next_token":null}"""

    /** Always another page. */
    const val ENDLESS_PAGE = """{"data":[],"next_token":"more"}"""

    const val HEART_RATE_PAGE =
      """{"data":[{"timestamp":"2026-08-08T15:10:00+00:00","bpm":140,"source":"workout"},{"timestamp":"2026-08-08T15:20:00+00:00","bpm":152,"source":"workout"}],"next_token":null}"""
  }
}
