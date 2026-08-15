package fi.merilainen.treenivalmentaja.data.strava

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connecting, failing to connect, and disconnecting Strava.
 *
 * As on the Oura side, the interesting cases are the ones where the login does *not* go as
 * intended — a redirect that is not ours, a `state` that does not match, a consent screen that
 * granted less than was asked for.
 */
class StravaConnectionTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = TOKEN_BODY

  private val store = FakeStravaTokenStorage()
  private var clearedRows = 0

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/oauth/token") { exchange: HttpExchange ->
      exchange.requestBody.use { it.readBytes() }
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

  private fun connection() =
    StravaConnection(
      store = store,
      authService =
        StravaAuthService(
          credentials = { store.credentials() },
          tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
        ),
      onDisconnected = { clearedRows++ },
    )

  @Test
  fun `without credentials there is nothing to connect with`() = runTest {
    val connection = connection()

    connection.refreshState()

    assertEquals(StravaConnectionState.NotConfigured, connection.state.value)
    assertNull(connection.beginAuthorization())
  }

  @Test
  fun `saving credentials moves the card to disconnected`() = runTest {
    val connection = connection()

    assertTrue(connection.saveCredentials("client-1", "secret-1"))

    assertEquals(StravaConnectionState.Disconnected, connection.state.value)
    assertEquals("client-1", store.storedCredentials?.clientId)
  }

  @Test
  fun `blank credentials are refused`() = runTest {
    val connection = connection()

    assertEquals(false, connection.saveCredentials("client-1", "   "))
    assertNull(store.storedCredentials)
  }

  /** Pasted values carry invisible whitespace; a trailing space must not become part of a secret. */
  @Test
  fun `credentials are trimmed`() = runTest {
    val connection = connection()

    connection.saveCredentials("  client-1  ", "  secret-1  ")

    assertEquals("client-1", store.storedCredentials?.clientId)
    assertEquals("secret-1", store.storedCredentials?.clientSecret)
  }

  @Test
  fun `beginning an authorization stores the state and returns a url`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")

    val url = connection.beginAuthorization()

    assertNotNull(url)
    assertTrue(url!!, url.startsWith("https://www.strava.com/oauth/mobile/authorize?"))
    // Written down *before* the URL is handed out: the browser must never come back to an app that
    // has forgotten what it asked.
    assertNotNull(store.state)
    assertTrue(url, url.contains("state=${store.state}"))
    assertEquals(StravaConnectionState.Connecting, connection.state.value)
  }

  @Test
  fun `a completed login stores the tokens`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()

    connection.completeAuthorization(
      "treenivalmentaja://localhost/strava?code=abc&scope=activity%3Aread_all&state=${store.state}"
    )

    assertEquals(StravaConnectionState.Connected, connection.state.value)
    assertEquals("access-1", store.tokens?.accessToken)
    assertEquals("refresh-1", store.tokens?.refreshToken)
    // Strava states expiry absolutely, in seconds; the store keeps millis.
    assertEquals(1_800_000_000_000L, store.tokens?.expiresAtUtc)
    // A state is good for one attempt whether it worked or not.
    assertNull(store.state)
  }

  /**
   * The forged-redirect case, and the reason `state` exists at all. Nothing is exchanged and no
   * token is stored — the exported activity handed us a request this device never made.
   */
  @Test
  fun `a redirect with the wrong state stores nothing`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()

    connection.completeAuthorization("treenivalmentaja://localhost/strava?code=abc&state=forged")

    assertTrue(connection.state.value is StravaConnectionState.Failed)
    assertNull(store.tokens)
  }

  /** Someone else's deep link leaves a login in progress alone rather than cancelling it. */
  @Test
  fun `an unrelated redirect does not disturb a login in flight`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()
    val pending = store.state

    connection.completeAuthorization("https://example.com/?code=abc&state=$pending")

    assertEquals(StravaConnectionState.Connecting, connection.state.value)
    assertEquals(pending, store.state)
  }

  /**
   * Strava lets the user untick scopes on the consent screen. A connection without permission to
   * read activities authenticates fine and then returns nothing forever, which from the app's side
   * is indistinguishable from an athlete who never trains — so it is refused where the reason can
   * still be said out loud.
   */
  @Test
  fun `a login granted without activity read is refused`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()

    connection.completeAuthorization(
      "treenivalmentaja://localhost/strava?code=abc&scope=read&state=${store.state}"
    )

    val state = connection.state.value
    assertTrue(state.toString(), state is StravaConnectionState.Failed)
    assertTrue(
      (state as StravaConnectionState.Failed).message,
      state.message.contains("harjoituksia"),
    )
    assertNull(store.tokens)
  }

  @Test
  fun `a denial is reported without a token`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()

    connection.completeAuthorization(
      "treenivalmentaja://localhost/strava?error=access_denied&state=${store.state}"
    )

    assertTrue(connection.state.value is StravaConnectionState.Failed)
    assertNull(store.tokens)
  }

  @Test
  fun `a rejected code leaves the connection failed`() = runTest {
    status = 400
    body = """{"message":"Bad Request"}"""
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()

    connection.completeAuthorization(
      "treenivalmentaja://localhost/strava?code=abc&state=${store.state}"
    )

    assertTrue(connection.state.value is StravaConnectionState.Failed)
    assertNull(store.tokens)
    assertNull(store.state)
  }

  /**
   * Disconnecting drops the tokens and the cached rows but **keeps the credentials**: connecting
   * again must not mean pasting a client id and secret a second time.
   */
  @Test
  fun `disconnecting keeps the credentials and clears the rows`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    store.tokens = StravaTokens("a", "r", 0)

    connection.disconnect()

    assertNull(store.tokens)
    assertEquals(1, clearedRows)
    assertEquals("client-1", store.storedCredentials?.clientId)
    assertEquals(StravaConnectionState.Disconnected, connection.state.value)
  }

  @Test
  fun `forgetting the credentials is the way back to a clean app`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    store.tokens = StravaTokens("a", "r", 0)

    connection.forgetCredentials()

    assertNull(store.tokens)
    assertNull(store.storedCredentials)
    assertEquals(StravaConnectionState.NotConfigured, connection.state.value)
  }

  @Test
  fun `backing out of the browser is not a failure`() = runTest {
    val connection = connection()
    connection.saveCredentials("client-1", "secret-1")
    connection.beginAuthorization()

    connection.cancelAuthorization()

    assertEquals(StravaConnectionState.Disconnected, connection.state.value)
    assertNull(store.state)
  }

  private companion object {
    /** `expires_at` is epoch **seconds** — the store's millis are what proves the unit change. */
    const val TOKEN_BODY =
      """{"access_token":"access-1","refresh_token":"refresh-1","expires_at":1800000000}"""
  }
}
