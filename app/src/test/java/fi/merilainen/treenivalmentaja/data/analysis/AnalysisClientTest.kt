package fi.merilainen.treenivalmentaja.data.analysis

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * All three clients, against a local HTTP server — the same way the Oura and intervals.icu clients
 * are tested, and for the same reason: no real key is needed to prove how they behave, and a test
 * that needed one would cost money on every run.
 *
 * **The interesting half is where each provider hides its answer**, because that is where a bug is
 * silent: every status code says success and the card renders empty. Claude puts thinking blocks
 * first, OpenAI can filter content into a `200` with no text, and Gemini has two different ways of
 * returning nothing — one of which omits `candidates` entirely. One of these already shipped as a
 * real bug, so each provider gets its own fixture for it.
 *
 * What is *not* proven here is that the real services answer in these shapes. The fixtures are
 * written from each provider's documented format, so a service that changed shape would pass this
 * suite and fail on the phone. That limit is worth stating rather than glossing.
 */
class AnalysisClientTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = ""
  private var retryAfter: String? = null

  private var lastPath: String? = null
  private var lastBody: String? = null
  private var lastHeaders: Map<String, String> = emptyMap()

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange: HttpExchange ->
      lastPath = exchange.requestURI.path
      lastBody = exchange.requestBody.readBytes().decodeToString()
      lastHeaders =
        exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.first() }
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

  private fun base() = "http://127.0.0.1:${server.address.port}"

  private fun anthropic(key: String? = "sk-test") =
    AnthropicClient(apiKeys = { key }, baseUrl = base())

  private fun openAi(key: String? = "sk-test") = OpenAiClient(apiKeys = { key }, baseUrl = base())

  private fun gemini(key: String? = "AIza-test") = GeminiClient(apiKeys = { key }, baseUrl = base())

  // ================================================================== Anthropic

  /**
   * The bug that shipped: on the models that think, `content[0]` is a thinking block whose text is
   * empty, so reading index zero renders a blank card while every status code says success.
   */
  @Test
  fun `claude reads the text block even when a thinking block comes first`() = runTest {
    body =
      """{"stop_reason":"end_turn","content":[
        {"type":"thinking","thinking":""},
        {"type":"text","text":"Harjoitus meni hyvin."}]}"""

    assertEquals(
      "Harjoitus meni hyvin.",
      anthropic().analyse("p", AnalysisModel.CLAUDE_SONNET),
    )
  }

  /** Haiku does not think, so block zero really is the text — the other half of the same fix. */
  @Test
  fun `claude reads the text block when it is the only block`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Lepää tänään."}]}"""

    assertEquals("Lepää tänään.", anthropic().analyse("p", AnalysisModel.CLAUDE_HAIKU))
  }

  /** A refusal is a 200 whose content may be empty — checked before the list is walked. */
  @Test
  fun `claude reports a refusal rather than indexing an empty list`() = runTest {
    body = """{"stop_reason":"refusal","content":[]}"""

    val failure = runCatching { anthropic().analyse("p", AnalysisModel.CLAUDE_OPUS) }.exceptionOrNull()
    assertTrue(failure is AnalysisRefusedException)
  }

  @Test
  fun `claude sends its key and version headers, and no thinking config`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""

    anthropic().analyse("Analysoi", AnalysisModel.CLAUDE_OPUS)

    assertEquals("/v1/messages", lastPath)
    assertEquals("sk-test", lastHeaders["x-api-key"])
    assertEquals(AnthropicClient.API_VERSION, lastHeaders["anthropic-version"])
    assertTrue(lastBody!!.contains(AnalysisModel.CLAUDE_OPUS.id))
    assertFalse(lastBody!!.contains("thinking"))
  }

  // ================================================================== OpenAI

  @Test
  fun `chatgpt reads the first choice's message content`() = runTest {
    body =
      """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hyvin meni."}}]}"""

    assertEquals("Hyvin meni.", openAi().analyse("p", AnalysisModel.GPT_LUNA))
  }

  /** A filtered answer is a 200 with empty content — a refusal, not an unreadable response. */
  @Test
  fun `chatgpt reports a content filter as a refusal`() = runTest {
    body = """{"choices":[{"finish_reason":"content_filter","message":{"content":null}}]}"""

    val failure = runCatching { openAi().analyse("p", AnalysisModel.GPT_LUNA) }.exceptionOrNull()
    assertTrue(failure is AnalysisRefusedException)
  }

  /**
   * Truncation is not failure. The ceiling is 8192 tokens against an answer asked to be 110 words,
   * so `length` means the model spent the budget reasoning and still wrote prose worth showing.
   */
  @Test
  fun `chatgpt returns text that stopped at the token ceiling`() = runTest {
    body = """{"choices":[{"finish_reason":"length","message":{"content":"Alku katkesi"}}]}"""

    assertEquals("Alku katkesi", openAi().analyse("p", AnalysisModel.GPT_TERRA))
  }

  /**
   * `max_completion_tokens`, not `max_tokens`: the older field is deprecated and rejected outright
   * by the reasoning-capable models, which is every model this app offers.
   */
  @Test
  fun `chatgpt sends a bearer token and the newer token-limit field`() = runTest {
    body = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""

    openAi().analyse("p", AnalysisModel.GPT_SOL)

    assertEquals("/v1/chat/completions", lastPath)
    assertEquals("Bearer sk-test", lastHeaders["authorization"])
    assertTrue(lastBody!!.contains("max_completion_tokens"))
    assertFalse(lastBody!!.contains("\"max_tokens\""))
  }

  // ================================================================== Gemini

  @Test
  fun `gemini reads the first candidate's parts`() = runTest {
    body =
      """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"Palautuminen laskee."}]}}]}"""

    assertEquals("Palautuminen laskee.", gemini().analyse("p", AnalysisModel.GEMINI_FLASH))
  }

  /** A long answer can arrive split; reading only part one would silently truncate it. */
  @Test
  fun `gemini joins several parts`() = runTest {
    body =
      """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"Osa yksi. "},{"text":"Osa kaksi."}]}}]}"""

    assertEquals("Osa yksi. Osa kaksi.", gemini().analyse("p", AnalysisModel.GEMINI_FLASH))
  }

  /**
   * The shape unique to Gemini: a blocked prompt returns a `200` with **no `candidates` key at
   * all**, so reaching for element zero would throw rather than read empty.
   */
  @Test
  fun `gemini reports a blocked prompt with no candidates at all`() = runTest {
    body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""

    val failure = runCatching { gemini().analyse("p", AnalysisModel.GEMINI_FLASH) }.exceptionOrNull()
    assertTrue(failure is AnalysisRefusedException)
  }

  /** The other Gemini emptiness: a candidate exists but its output was filtered. */
  @Test
  fun `gemini reports a safety-filtered candidate as a refusal`() = runTest {
    body = """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}"""

    val failure = runCatching { gemini().analyse("p", AnalysisModel.GEMINI_FLASH) }.exceptionOrNull()
    assertTrue(failure is AnalysisRefusedException)
  }

  /**
   * The key goes in a header, never the query string. Google documents `?key=`, and it works — but a
   * secret in a URL is a secret in every proxy log and crash report along the way.
   */
  @Test
  fun `gemini sends the key as a header and never in the query string`() = runTest {
    body = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"ok"}]}}]}"""

    gemini().analyse("p", AnalysisModel.GEMINI_FLASH)

    assertEquals("AIza-test", lastHeaders["x-goog-api-key"])
    assertEquals("/v1beta/models/${AnalysisModel.GEMINI_FLASH.id}:generateContent", lastPath)
    assertTrue(lastBody!!.contains("maxOutputTokens"))
  }

  /**
   * Gemini answers `400` for a bad key where the other two answer `401`. Left alone that reads as
   * "pyyntö hylättiin (HTTP 400)" — telling the owner their app is broken when their key is wrong.
   */
  @Test
  fun `gemini maps 400 to an auth failure rather than a bad request`() = runTest {
    status = 400
    body = """{"error":{"code":400,"message":"API key not valid"}}"""

    val failure = runCatching { gemini().analyse("p", AnalysisModel.GEMINI_FLASH) }.exceptionOrNull()
    assertTrue(failure is AnalysisAuthException)
  }

  /** The same 400 from the other two really is a malformed request, and stays one. */
  @Test
  fun `claude maps 400 to a request error`() = runTest {
    status = 400
    body = """{"error":{"type":"invalid_request_error"}}"""

    val failure = runCatching { anthropic().analyse("p", AnalysisModel.CLAUDE_SONNET) }.exceptionOrNull()
    assertTrue(failure is AnalysisRequestException)
  }

  // ================================================================== shared

  @Test
  fun `every client refuses without a key and makes no request`() = runTest {
    listOf(
        anthropic(key = null) to AnalysisModel.CLAUDE_SONNET,
        openAi(key = null) to AnalysisModel.GPT_LUNA,
        gemini(key = null) to AnalysisModel.GEMINI_FLASH,
      )
      .forEach { (client, model) ->
        val failure = runCatching { client.analyse("p", model) }.exceptionOrNull()
        assertTrue(failure is AnalysisNotConfiguredException)
      }
    assertNull(lastPath)
  }

  @Test
  fun `401 is a non-retryable auth failure for every provider`() = runTest {
    status = 401
    body = "{}"

    listOf(
        anthropic() to AnalysisModel.CLAUDE_SONNET,
        openAi() to AnalysisModel.GPT_LUNA,
      )
      .forEach { (client, model) ->
        val failure = runCatching { client.analyse("p", model) }.exceptionOrNull()
        assertTrue(failure is AnalysisAuthException)
        assertFalse((failure as AnalysisException).canRetry)
      }
  }

  @Test
  fun `429 carries the service's own Retry-After and is retryable`() = runTest {
    status = 429
    retryAfter = "30"
    body = "{}"

    val failure = runCatching { openAi().analyse("p", AnalysisModel.GPT_LUNA) }.exceptionOrNull()
    assertTrue(failure is AnalysisRateLimitException)
    assertEquals(30L, (failure as AnalysisRateLimitException).retryAfterSeconds)
    assertTrue(failure.canRetry)
  }

  @Test
  fun `a retired model id reads as a gone model, not a broken key`() = runTest {
    status = 404
    body = "{}"

    val failure = runCatching { openAi().analyse("p", AnalysisModel.GPT_SOL) }.exceptionOrNull()
    assertTrue(failure is AnalysisModelGoneException)
  }

  @Test
  fun `server trouble is retryable for every provider`() = runTest {
    status = 503
    body = "{}"

    val failure = runCatching { gemini().analyse("p", AnalysisModel.GEMINI_FLASH) }.exceptionOrNull()
    assertTrue(failure is AnalysisOverloadedException)
    assertTrue((failure as AnalysisException).canRetry)
  }

  @Test
  fun `a non-JSON body is unreadable rather than a crash`() = runTest {
    body = "<html>gateway error</html>"

    val failure = runCatching { anthropic().analyse("p", AnalysisModel.CLAUDE_SONNET) }.exceptionOrNull()
    assertTrue(failure is AnalysisUnavailableException)
  }

  /** An empty answer must not render as a successful blank card, whichever provider produced it. */
  @Test
  fun `an empty answer is a failure for every provider`() = runTest {
    body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"   "}]}"""
    assertTrue(
      runCatching { anthropic().analyse("p", AnalysisModel.CLAUDE_SONNET) }.exceptionOrNull()
        is AnalysisUnavailableException
    )

    body = """{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""
    assertTrue(
      runCatching { openAi().analyse("p", AnalysisModel.GPT_LUNA) }.exceptionOrNull()
        is AnalysisUnavailableException
    )

    body = """{"candidates":[{"finishReason":"STOP","content":{"parts":[]}}]}"""
    assertTrue(
      runCatching { gemini().analyse("p", AnalysisModel.GEMINI_FLASH) }.exceptionOrNull()
        is AnalysisUnavailableException
    )
  }
}
