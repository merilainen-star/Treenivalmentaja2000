package fi.merilainen.treenivalmentaja.data.oura

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connecting, failing to connect, and disconnecting.
 *
 * The interesting cases are the ones where the login does *not* go as intended: a redirect that is
 * not ours, a `state` that does not match, a process that forgot its verifier. Those are the states
 * a user meets on a bad day and the ones a browser round trip makes easy to get wrong.
 */
class OuraConnectionTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = TOKEN_BODY
  private var tokenRequests = 0

  private val store = FakeOuraTokenStorage()
  private var clearedRows = 0

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/oauth/token") { exchange: HttpExchange ->
      tokenRequests++
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

  private fun connection(credentials: OuraCredentials = CONFIGURED) =
    OuraConnection(
      store = store,
      authService =
        OuraAuthService(
          credentials = { credentials },
          tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
        ),
      credentials = { credentials },
      onDisconnected = { clearedRows++ },
    )

  /**
   * A connection wired the way the application wires it: credentials come from the store, falling
   * back to whatever the build was compiled with. This is what the credential fields in Settings
   * actually feed.
   */
  private fun connectionReadingStoredCredentials() =
    OuraConnection(
      store = store,
      authService =
        OuraAuthService(
          credentials = { store.credentials() ?: PLACEHOLDERS },
          tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
        ),
      credentials = { store.credentials() ?: PLACEHOLDERS },
      onDisconnected = { clearedRows++ },
    )

  /** Runs a whole login and returns the connection, so the failure cases can start from here. */
  private suspend fun connected(): OuraConnection {
    val connection = connection()
    connection.beginAuthorization()
    connection.completeAuthorization(redirect(code = "the-code", state = store.state))
    return connection
  }

  private fun redirect(code: String? = null, state: String?, error: String? = null): String =
    buildString {
      append("treenivalmentaja://oauth2callback?")
      code?.let { append("code=$it&") }
      error?.let { append("error=$it&") }
      append("state=$state")
    }

  // ------------------------------------------------------------------ before anything happens

  @Test
  fun `a build without credentials is not merely disconnected`() = runTest {
    val connection = connection(credentials = PLACEHOLDERS)

    assertEquals(OuraConnectionState.NotConfigured, connection.state.value)
  }

  @Test
  fun `a build without credentials opens no browser`() = runTest {
    val connection = connection(credentials = PLACEHOLDERS)

    assertNull(connection.beginAuthorization())
    assertNull(store.verifier)
  }

  /**
   * A build with no credentials cannot have started a login, so a redirect reaching it is someone
   * else's. Found on a device rather than reasoned about: firing a forged redirect at an
   * unconfigured build left the Settings card offering "Yritä uudelleen" for a connection that
   * cannot be attempted at all.
   */
  @Test
  fun `a redirect to a build without credentials does not invent a failure`() = runTest {
    val connection = connection(credentials = PLACEHOLDERS)

    connection.completeAuthorization(redirect(code = "whatever", state = "anything"))

    assertEquals(OuraConnectionState.NotConfigured, connection.state.value)
    assertEquals(0, tokenRequests)
  }

  @Test
  fun `stored tokens are found at startup`() = runTest {
    store.tokens = OuraTokens("access", "refresh", OuraTokens.UNKNOWN_EXPIRY)
    val connection = connection()

    connection.refreshState()

    assertEquals(OuraConnectionState.Connected, connection.state.value)
  }

  // ------------------------------------------------------------------ credentials, typed in

  /**
   * The step that makes this feature reachable from a phone.
   *
   * Oura withdrew personal access tokens, so an application registered in their developer portal is
   * the only way in — and compiling its secret into the build would mean a PC and a local build,
   * which is not how this app gets installed. See ADR-009.
   */
  @Test
  fun `saving credentials moves the card from the fields to the connect button`() = runTest {
    val connection = connectionReadingStoredCredentials()
    connection.refreshState()
    assertEquals(OuraConnectionState.NotConfigured, connection.state.value)

    val saved = connection.saveCredentials("client-abc", "secret-xyz")

    assertTrue(saved)
    assertEquals(OuraConnectionState.Disconnected, connection.state.value)
    assertEquals("client-abc", store.storedCredentials!!.clientId)
  }

  /** Pasted values carry invisible whitespace, and Oura would reject them without saying why. */
  @Test
  fun `pasted credentials are trimmed`() = runTest {
    val connection = connectionReadingStoredCredentials()

    connection.saveCredentials("  client-abc\n", " secret-xyz ")

    assertEquals("client-abc", store.storedCredentials!!.clientId)
    assertEquals("secret-xyz", store.storedCredentials!!.clientSecret)
  }

  @Test
  fun `a blank field saves nothing`() = runTest {
    val connection = connectionReadingStoredCredentials()

    assertFalse(connection.saveCredentials("client-abc", "   "))
    assertFalse(connection.saveCredentials("", "secret-xyz"))
    assertNull(store.storedCredentials)
  }

  /** A login begun under the old credentials cannot be finished under the new ones. */
  @Test
  fun `changing credentials abandons a login in progress`() = runTest {
    val connection = connectionReadingStoredCredentials()
    connection.saveCredentials("client-abc", "secret-xyz")
    connection.beginAuthorization()
    assertNotNull(store.verifier)

    connection.saveCredentials("client-def", "secret-uvw")

    assertNull(store.verifier)
  }

  @Test
  fun `forgetting the credentials returns to the fields and takes the tokens with it`() = runTest {
    val connection = connectionReadingStoredCredentials()
    connection.saveCredentials("client-abc", "secret-xyz")
    store.tokens = OuraTokens("access", "refresh", OuraTokens.UNKNOWN_EXPIRY)

    connection.forgetCredentials()

    assertNull(store.storedCredentials)
    assertNull(store.tokens)
    assertEquals(1, clearedRows)
    assertEquals(OuraConnectionState.NotConfigured, connection.state.value)
  }

  /** Disconnecting is not the same as forgetting: reconnecting must not mean pasting them again. */
  @Test
  fun `disconnecting keeps the credentials`() = runTest {
    val connection = connectionReadingStoredCredentials()
    connection.saveCredentials("client-abc", "secret-xyz")
    store.tokens = OuraTokens("access", "refresh", OuraTokens.UNKNOWN_EXPIRY)

    connection.disconnect()

    assertEquals("client-abc", store.storedCredentials!!.clientId)
    assertEquals(OuraConnectionState.Disconnected, connection.state.value)
  }

  // ------------------------------------------------------------------ the happy path

  /** The verifier is written down before the URL exists, so a fast redirect cannot outrun it. */
  @Test
  fun `starting a login stores the verifier and the state`() = runTest {
    val connection = connection()

    val url = connection.beginAuthorization()!!

    assertTrue(store.verifier!!.isNotBlank())
    assertTrue(url, url.contains("state=${store.state}"))
    assertEquals(OuraConnectionState.Connecting, connection.state.value)
  }

  @Test
  fun `the challenge in the url matches the stored verifier`() = runTest {
    val connection = connection()

    val url = connection.beginAuthorization()!!

    assertTrue(url, url.contains("code_challenge=${OuraOAuth.codeChallengeOf(store.verifier!!)}"))
  }

  @Test
  fun `a valid redirect completes the login and stores the tokens`() = runTest {
    val connection = connected()

    assertEquals(OuraConnectionState.Connected, connection.state.value)
    assertEquals("access-1", store.tokens!!.accessToken)
    assertEquals("refresh-1", store.tokens!!.refreshToken)
  }

  /** One attempt per verifier: keeping it would leave a spent secret on disk. */
  @Test
  fun `a finished login leaves nothing pending`() = runTest {
    connected()

    assertNull(store.verifier)
    assertNull(store.state)
  }

  // ------------------------------------------------------------------ when it goes wrong

  /**
   * The exported activity can be started by anything on the device. A redirect whose `state` is
   * not the one this device generated must not reach the token endpoint at all.
   */
  @Test
  fun `a forged redirect never reaches the token endpoint`() = runTest {
    val connection = connection()
    connection.beginAuthorization()

    connection.completeAuthorization(redirect(code = "attacker-code", state = "not-our-state"))

    assertEquals(0, tokenRequests)
    assertNull(store.tokens)
    assertTrue(connection.state.value is OuraConnectionState.Failed)
  }

  @Test
  fun `a forged redirect also throws away the pending login`() = runTest {
    val connection = connection()
    connection.beginAuthorization()

    connection.completeAuthorization(redirect(code = "attacker-code", state = "not-our-state"))

    assertNull(store.verifier)
  }

  /** Some other app's deep link. Whatever is in flight is left alone rather than cancelled. */
  @Test
  fun `an unrelated deep link does not disturb a login in progress`() = runTest {
    val connection = connection()
    connection.beginAuthorization()
    val verifier = store.verifier

    connection.completeAuthorization("https://example.com/?code=abc&state=${store.state}")

    assertEquals(OuraConnectionState.Connecting, connection.state.value)
    assertEquals(verifier, store.verifier)
    assertEquals(0, tokenRequests)
  }

  @Test
  fun `refusing at Oura is reported in Finnish and is not an error state to retry blindly`() =
    runTest {
      val connection = connection()
      connection.beginAuthorization()

      connection.completeAuthorization(redirect(error = "access_denied", state = store.state))

      val failed = connection.state.value as OuraConnectionState.Failed
      assertTrue(failed.message, failed.message.contains("ei hyväksytty"))
      assertEquals(0, tokenRequests)
    }

  /**
   * The process died during the browser round trip and came back without its verifier. Without it
   * the code cannot be exchanged, and saying so beats a silent failure.
   */
  @Test
  fun `a redirect with no verifier left is a failure, not an exchange`() = runTest {
    val connection = connection()
    connection.beginAuthorization()
    store.verifier = null

    connection.completeAuthorization(redirect(code = "the-code", state = store.state))

    assertEquals(0, tokenRequests)
    assertTrue(connection.state.value is OuraConnectionState.Failed)
  }

  @Test
  fun `a rejected code leaves the connection failed and empty`() = runTest {
    status = 400
    body = """{"error":"invalid_grant"}"""
    val connection = connection()
    connection.beginAuthorization()

    connection.completeAuthorization(redirect(code = "stale", state = store.state))

    assertTrue(connection.state.value is OuraConnectionState.Failed)
    assertNull(store.tokens)
    assertNull(store.verifier)
  }

  @Test
  fun `backing out of the browser is not a failure`() = runTest {
    val connection = connection()
    connection.beginAuthorization()

    connection.cancelAuthorization()

    assertEquals(OuraConnectionState.Disconnected, connection.state.value)
    assertNull(store.verifier)
  }

  @Test
  fun `a read failure message can be dismissed without reconnecting`() = runTest {
    val connection = connection()
    connection.beginAuthorization()
    connection.completeAuthorization(redirect(code = "c", state = "wrong"))

    connection.dismissFailure()

    assertEquals(OuraConnectionState.Disconnected, connection.state.value)
  }

  // ------------------------------------------------------------------ disconnecting

  @Test
  fun `disconnecting drops the tokens and the cached rows`() = runTest {
    val connection = connected()

    connection.disconnect()

    assertNull(store.tokens)
    assertEquals(1, clearedRows)
    assertEquals(OuraConnectionState.Disconnected, connection.state.value)
  }

  // ------------------------------------------------------------------ handing out the token

  @Test
  fun `the token source reads the store rather than a copy taken at startup`() = runTest {
    val connection = connection()
    val source = connection.tokenSource()
    assertNull(source.accessToken())

    store.tokens = OuraTokens("later-token", null, OuraTokens.UNKNOWN_EXPIRY)

    assertEquals("later-token", source.accessToken())
  }

  @Test
  fun `disconnecting takes the access token away from the client too`() = runTest {
    val connection = connected()
    val source = connection.tokenSource()
    assertFalse(source.accessToken().isNullOrEmpty())

    connection.disconnect()

    assertNull(source.accessToken())
  }

  private companion object {
    val CONFIGURED = OuraCredentials(clientId = "client-abc", clientSecret = "secret-xyz")

    val PLACEHOLDERS =
      OuraCredentials(clientId = "placeholder_client_id", clientSecret = "placeholder_client_secret")

    const val TOKEN_BODY =
      """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":86400}"""
  }
}
