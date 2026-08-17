package fi.merilainen.treenivalmentaja.data.anthropic

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.domain.AnthropicModel
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The client, against a local HTTP server — the same way the Oura and intervals.icu clients are
 * tested, and for the same reason: no real API key is needed to prove how this behaves, and a test
 * that needed one would cost money to run.
 *
 * What is *not* proven here is that Anthropic's own answers match these shapes. The fixtures are
 * written from the API's documented response format, so a service that changed shape would pass this
 * suite and fail on the phone. That limit is worth stating rather than glossing.
 */
class AnthropicClientTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = ""
  private var retryAfter: String? = null

  /** What the last request carried, so the test can assert on it. */
  private var lastPath: String? = null
  private var lastApiKey: String? = null
  private var lastVersion: String? = null
  private var lastBody: String? = null

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange: HttpExchange ->
      lastPath = exchange.requestURI.path
      lastApiKey = exchange.requestHeaders.getFirst("x-api-key")
      lastVersion = exchange.requestHeaders.getFirst("anthropic-version")
      lastBody = exchange.requestBody.readBytes().decodeToString()
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

  private fun client(key: String? = "sk-test") =
    AnthropicClient(
      apiKeys = { key },
      baseUrl = "http://127.0.0.1:${server.address.port}",
    )

  // ------------------------------------------------------------------ the content-block scan

  /**
   * **The regression this whole class exists for.**
   *
   * On the two models that think, the thinking blocks come *first* and their text is empty, so
   * `content[0].text` is `""` — an answer that renders as a blank card while every status code says
   * success. Reading the first block whose `type` is `"text"` is the fix, and this is the fixture
   * that would have caught the bug.
   */
  @Test
  fun `reads the text block even when a thinking block comes first`() = runTest {
    body =
      """
      {"stop_reason":"end_turn","content":[
        {"type":"thinking","thinking":""},
        {"type":"text","text":"Harjoitus meni hyvin."}
      ]}
      """
        .trimIndent()

    assertEquals("Harjoitus meni hyvin.", client().analyse("prompt", AnthropicModel.SONNET))
  }

  /** The non-thinking shape — Haiku's — still works, which is the other half of the same fix. */
  @Test
  fun `reads the text block when it is the only block`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Lepää tänään."}]}"""

    assertEquals("Lepää tänään.", client().analyse("prompt", AnthropicModel.HAIKU))
  }

  /** A response with only a thinking block is not a success with empty prose. */
  @Test
  fun `treats a response with no text block as unavailable`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""}]}"""

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()
    assertTrue(failure is AnthropicUnavailableException)
  }

  // ------------------------------------------------------------------ refusal

  /**
   * A refusal is a `200`, and its `content` may be empty — so `stop_reason` has to be read before
   * the list is walked, or the one response designed to carry nothing is the one that crashes.
   */
  @Test
  fun `reports a refusal rather than indexing an empty content list`() = runTest {
    body = """{"stop_reason":"refusal","content":[]}"""

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.OPUS) }.exceptionOrNull()
    assertTrue(failure is AnthropicRefusedException)
    assertFalse((failure as AnthropicException).canRetry)
  }

  // ------------------------------------------------------------------ the request

  @Test
  fun `sends the key, the version and the prompt`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""

    client().analyse("Analysoi tämä", AnthropicModel.OPUS)

    assertEquals("/v1/messages", lastPath)
    assertEquals("sk-test", lastApiKey)
    assertEquals(AnthropicClient.API_VERSION, lastVersion)
    assertTrue(lastBody!!.contains("\"model\":\"${AnthropicModel.OPUS.id}\""))
    assertTrue(lastBody!!.contains("Analysoi tämä"))
  }

  /**
   * No `thinking` field, which is what lets one request body serve all three models: the two that
   * think do so adaptively when it is absent, and the one that does not would reject a
   * configuration for a feature it lacks.
   */
  @Test
  fun `sends no thinking configuration`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""

    client().analyse("prompt", AnthropicModel.SONNET)

    assertFalse(lastBody!!.contains("thinking"))
  }

  @Test
  fun `refuses without a key and makes no request`() = runTest {
    val failure =
      runCatching { client(key = null).analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()

    assertTrue(failure is AnthropicNotConfiguredException)
    assertEquals(null, lastPath)
  }

  // ------------------------------------------------------------------ status codes

  @Test
  fun `maps 401 to an auth failure that cannot be retried`() = runTest {
    status = 401
    body = """{"error":{"type":"authentication_error"}}"""

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()
    assertTrue(failure is AnthropicAuthException)
    assertFalse((failure as AnthropicException).canRetry)
  }

  /**
   * A retired model id, which is reachable precisely because the model list is a constant in this
   * app. Distinct from a bad key, because the remedy is a different knob.
   */
  @Test
  fun `maps 404 to a gone-model failure`() = runTest {
    status = 404
    body = """{"error":{"type":"not_found_error"}}"""

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()
    assertTrue(failure is AnthropicModelGoneException)
  }

  @Test
  fun `maps 429 and carries the service's own Retry-After`() = runTest {
    status = 429
    retryAfter = "30"
    body = """{"error":{"type":"rate_limit_error"}}"""

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()
    assertTrue(failure is AnthropicRateLimitException)
    assertEquals(30L, (failure as AnthropicRateLimitException).retryAfterSeconds)
    assertTrue(failure.canRetry)
  }

  /** Overload is retryable and spends no quota, so it is not the same state as a rate limit. */
  @Test
  fun `maps 529 to a retryable overload`() = runTest {
    status = 529
    body = """{"error":{"type":"overloaded_error"}}"""

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()
    assertTrue(failure is AnthropicOverloadedException)
    assertTrue((failure as AnthropicException).canRetry)
  }

  @Test
  fun `treats a non-JSON body as unreadable rather than crashing`() = runTest {
    body = "<html>gateway error</html>"

    val failure =
      runCatching { client().analyse("prompt", AnthropicModel.SONNET) }.exceptionOrNull()
    assertTrue(failure is AnthropicUnavailableException)
  }
}
