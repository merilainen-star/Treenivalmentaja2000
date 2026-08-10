package fi.merilainen.treenivalmentaja.data.oura

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The token endpoint, against a local server.
 *
 * As with the collection client, the bodies here follow the OAuth2 shapes the specification's
 * `securitySchemes` implies rather than responses captured from Oura — nobody has run this against
 * the live token endpoint yet, and it needs a registered application to do so.
 */
class OuraAuthServiceTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = ""

  /** The form the last request posted, decoded. */
  private var posted: Map<String, String> = emptyMap()

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/oauth/token") { exchange: HttpExchange ->
      posted = formOf(exchange.requestBody.bufferedReader().use { it.readText() })
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

  private fun service(
    credentials: OuraCredentials = CONFIGURED,
    now: () -> Long = { FIXED_NOW },
  ) =
    OuraAuthService(
      credentials = { credentials },
      tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
      now = now,
    )

  private fun formOf(raw: String): Map<String, String> =
    raw
      .split('&')
      .filter { it.isNotEmpty() }
      .associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to
          URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
      }

  // ------------------------------------------------------------------ exchanging a code

  @Test
  fun `an authorization code is exchanged for tokens`() = runTest {
    body = TOKEN_BODY

    val tokens = service().exchange(code = "the-code", codeVerifier = "the-verifier")

    assertEquals("access-1", tokens.accessToken)
    assertEquals("refresh-1", tokens.refreshToken)
  }

  /** The verifier is what proves this exchange belongs to the request that started the flow. */
  @Test
  fun `the exchange sends the PKCE verifier and the redirect uri`() = runTest {
    body = TOKEN_BODY

    service().exchange(code = "the-code", codeVerifier = "the-verifier")

    assertEquals("authorization_code", posted["grant_type"])
    assertEquals("the-code", posted["code"])
    assertEquals("the-verifier", posted["code_verifier"])
    assertEquals("treenivalmentaja://oauth2callback", posted["redirect_uri"])
  }

  /** Oura's token endpoint requires client authentication — half the reason ADR-006 exists. */
  @Test
  fun `the exchange authenticates the client`() = runTest {
    body = TOKEN_BODY

    service().exchange("c", "v")

    assertEquals("client-abc", posted["client_id"])
    assertEquals("secret-xyz", posted["client_secret"])
  }

  /** `expires_in` is a duration; what is stored is the moment it runs out. */
  @Test
  fun `the relative expiry becomes an absolute one`() = runTest {
    body = TOKEN_BODY

    val tokens = service().exchange("c", "v")

    assertEquals(FIXED_NOW + 86_400 * 1000L, tokens.expiresAtUtc)
  }

  /** Nothing to be proactive with, so nothing pretends to know. A 401 is what renews then. */
  @Test
  fun `an endpoint that does not say when it expires leaves the expiry unknown`() = runTest {
    body = """{"access_token":"access-1","refresh_token":"refresh-1","token_type":"Bearer"}"""

    val tokens = service().exchange("c", "v")

    assertEquals(OuraTokens.UNKNOWN_EXPIRY, tokens.expiresAtUtc)
    assertEquals(false, tokens.isExpired(FIXED_NOW + 10_000_000))
  }

  // ------------------------------------------------------------------ refreshing

  @Test
  fun `a refresh spends the refresh token and takes the rotated one`() = runTest {
    body = """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":86400}"""

    val tokens = service().refresh("refresh-1")

    assertEquals("refresh_token", posted["grant_type"])
    assertEquals("refresh-1", posted["refresh_token"])
    assertEquals("access-2", tokens.accessToken)
    assertEquals("refresh-2", tokens.refreshToken)
  }

  // ------------------------------------------------------------------ failures

  /** A spent or revoked refresh token. The only way forward is connecting again. */
  @Test
  fun `invalid_grant says to connect again`() = runTest {
    status = 400
    body = """{"error":"invalid_grant","error_description":"code expired"}"""

    val failure = runCatching { service().exchange("c", "v") }.exceptionOrNull()

    val rejected = failure as OuraAuthorizationException
    assertTrue(rejected.message!!, rejected.message!!.contains("Yhdistä Oura uudelleen"))
    assertEquals(false, rejected.canRetry)
  }

  /** Not the user's problem at all: the build's credentials are wrong. */
  @Test
  fun `invalid_client points at the env file`() = runTest {
    status = 401
    body = """{"error":"invalid_client"}"""

    val failure = runCatching { service().exchange("c", "v") }.exceptionOrNull()

    assertTrue(failure!!.message!!, failure.message!!.contains(".env"))
  }

  @Test
  fun `a body that is not JSON is a readable failure`() = runTest {
    status = 200
    body = "<html>gateway</html>"

    val failure = runCatching { service().exchange("c", "v") }.exceptionOrNull()

    assertTrue(failure is OuraAuthorizationException)
  }

  @Test
  fun `a success without an access token is a failure`() = runTest {
    body = """{"token_type":"Bearer","expires_in":86400}"""

    val failure = runCatching { service().exchange("c", "v") }.exceptionOrNull()

    assertTrue(failure is OuraAuthorizationException)
  }

  @Test
  fun `an unreachable endpoint is a network failure, not a rejection`() = runTest {
    val offline =
      OuraAuthService(credentials = { CONFIGURED }, tokenUrl = "http://127.0.0.1:1/oauth/token")

    val failure = runCatching { offline.exchange("c", "v") }.exceptionOrNull()

    // Retryable, unlike a rejection: nothing about the credentials is known to be wrong.
    val unavailable = failure as OuraUnavailableException
    assertEquals(OuraClient.OFFLINE, unavailable.message)
    assertTrue(unavailable.canRetry)
  }

  /** A build with no `.env` must not post its placeholders to Oura. */
  @Test
  fun `an unconfigured build never reaches the endpoint`() = runTest {
    val failure =
      runCatching { service(credentials = PLACEHOLDERS).exchange("c", "v") }.exceptionOrNull()

    assertTrue(failure is OuraNotConfiguredException)
    assertEquals(emptyMap<String, String>(), posted)
  }

  // ------------------------------------------------------------------ credentials

  @Test
  fun `placeholder credentials do not count as configured`() {
    assertEquals(false, PLACEHOLDERS.isConfigured)
    assertEquals(false, OuraCredentials("", "").isConfigured)
    assertEquals(false, OuraCredentials("client-abc", "").isConfigured)
    assertEquals(true, CONFIGURED.isConfigured)
  }

  // ------------------------------------------------------------------ expiry

  @Test
  fun `a token is treated as expired slightly before it is`() {
    val tokens = OuraTokens("a", "r", expiresAtUtc = FIXED_NOW + 30_000)

    // Inside the skew: renewing now beats a request expiring mid-flight.
    assertEquals(true, tokens.isExpired(FIXED_NOW))
    assertEquals(false, OuraTokens("a", "r", FIXED_NOW + 600_000).isExpired(FIXED_NOW))
  }

  @Test
  fun `a token with no known expiry is never proactively expired`() {
    assertNull(OuraTokens("a", null, OuraTokens.UNKNOWN_EXPIRY).refreshToken)
    assertEquals(
      false,
      OuraTokens("a", "r", OuraTokens.UNKNOWN_EXPIRY).isExpired(Long.MAX_VALUE / 2),
    )
  }

  private companion object {
    const val FIXED_NOW = 1_754_800_000_000L

    val CONFIGURED = OuraCredentials(clientId = "client-abc", clientSecret = "secret-xyz")

    val PLACEHOLDERS =
      OuraCredentials(clientId = "placeholder_client_id", clientSecret = "placeholder_client_secret")

    const val TOKEN_BODY =
      """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":86400,"token_type":"Bearer"}"""
  }
}
