package fi.merilainen.treenivalmentaja.data.oura

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Token renewal on `401`.
 *
 * Two of these are about behaviour under concurrency, which is where this class earns its
 * complexity: a refresh token is spent once, and two requests failing at the same moment must not
 * both try to spend it. The rest is about not looping forever.
 */
class OuraAuthenticatorTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":86400}"""

  private var tokenRequests = 0

  /** Held closed to keep a refresh in flight while a second caller arrives. */
  private var gate: CountDownLatch? = null

  private val store = FakeOuraTokenStorage()
  private var refreshFailures = 0

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/oauth/token") { exchange: HttpExchange ->
      tokenRequests++
      exchange.requestBody.use { it.readBytes() }
      gate?.await(5, TimeUnit.SECONDS)
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

  private fun authenticator() =
    OuraAuthenticator(
      store = store,
      service =
        OuraAuthService(
          credentials = { OuraCredentials("client-abc", "secret-xyz") },
          tokenUrl = "http://127.0.0.1:${server.address.port}/oauth/token",
        ),
      onRefreshFailed = { refreshFailures++ },
    )

  private fun unauthorized(sentToken: String, priorResponses: Int = 0): Response {
    val request =
      Request.Builder()
        .url("https://api.ouraring.com/v2/usercollection/daily_readiness")
        .header("Authorization", "Bearer $sentToken")
        .build()
    var prior: Response? = null
    repeat(priorResponses) {
      prior =
        Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(401)
          .message("Unauthorized")
          .priorResponse(prior)
          .build()
    }
    return Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(401)
      .message("Unauthorized")
      .priorResponse(prior)
      .build()
  }

  // ------------------------------------------------------------------ the ordinary case

  @Test
  fun `a stale access token is refreshed and the request retried with the new one`() {
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)

    val retry = authenticator().authenticate(null, unauthorized("access-1"))

    assertEquals("Bearer access-2", retry!!.header("Authorization"))
    assertEquals("access-2", store.tokens!!.accessToken)
  }

  /** Oura rotates refresh tokens; keeping the old one would end the connection at the next 401. */
  @Test
  fun `the rotated refresh token is what gets stored`() {
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)

    authenticator().authenticate(null, unauthorized("access-1"))

    assertEquals("refresh-2", store.tokens!!.refreshToken)
  }

  /** If a response ever omits one, the old refresh token is still the only one there is. */
  @Test
  fun `an endpoint that returns no new refresh token does not lose the old one`() {
    body = """{"access_token":"access-2","expires_in":86400}"""
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)

    authenticator().authenticate(null, unauthorized("access-1"))

    assertEquals("refresh-1", store.tokens!!.refreshToken)
  }

  // ------------------------------------------------------------------ not looping

  /** Returning a request makes OkHttp reissue it. Doing that on the retry's own 401 never ends. */
  @Test
  fun `a request that already failed twice is given up on`() {
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)

    val retry = authenticator().authenticate(null, unauthorized("access-1", priorResponses = 1))

    assertNull(retry)
    assertEquals(0, tokenRequests)
  }

  @Test
  fun `a 401 with no tokens at all is not something to refresh`() {
    val retry = authenticator().authenticate(null, unauthorized("whatever"))

    assertNull(retry)
    assertEquals(0, tokenRequests)
  }

  @Test
  fun `a connection with no refresh token cannot renew itself`() {
    store.tokens = OuraTokens("access-1", refreshToken = null, OuraTokens.UNKNOWN_EXPIRY)

    val retry = authenticator().authenticate(null, unauthorized("access-1"))

    assertNull(retry)
    assertEquals(0, tokenRequests)
  }

  // ------------------------------------------------------------------ concurrency

  /**
   * The request carried a token that is no longer the stored one, which means somebody else already
   * refreshed while it was in flight. Refreshing again would spend a fresh refresh token for
   * nothing — and, if Oura had already invalidated it, log the user out for being busy.
   */
  @Test
  fun `a caller that lost the race retries with the new token instead of refreshing`() {
    store.tokens = OuraTokens("access-2", "refresh-2", OuraTokens.UNKNOWN_EXPIRY)

    val retry = authenticator().authenticate(null, unauthorized("access-1"))

    assertEquals("Bearer access-2", retry!!.header("Authorization"))
    assertEquals(0, tokenRequests)
  }

  /** The same thing, actually raced: two threads, one refresh. */
  @Test
  fun `two simultaneous failures produce one refresh`() {
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)
    val authenticator = authenticator()
    val open = CountDownLatch(1)
    gate = open
    val both = CountDownLatch(2)
    val retries = mutableListOf<Request?>()

    val threads =
      (1..2).map {
        Thread {
            val retry = authenticator.authenticate(null, unauthorized("access-1"))
            synchronized(retries) { retries += retry }
            both.countDown()
          }
          .apply { start() }
      }
    // Let the first refresh finish only once both threads are certainly inside.
    Thread.sleep(200)
    open.countDown()
    both.await(10, TimeUnit.SECONDS)
    threads.forEach { it.join(5_000) }

    assertEquals(1, tokenRequests)
    assertEquals(2, retries.size)
    assertTrue(retries.all { it?.header("Authorization") == "Bearer access-2" })
  }

  // ------------------------------------------------------------------ giving up

  /**
   * A rejected refresh token cannot be recovered from. Keeping it would make every later request
   * repeat this, so it is dropped and the UI is told — which is what turns a dead connection into
   * a visible "connect again" rather than a permanent silent failure.
   */
  @Test
  fun `a rejected refresh token clears the connection and says so`() {
    status = 400
    body = """{"error":"invalid_grant"}"""
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)

    val retry = authenticator().authenticate(null, unauthorized("access-1"))

    assertNull(retry)
    assertNull(store.tokens)
    assertEquals(1, refreshFailures)
  }

  @Test
  fun `a renewed token that cannot be stored never leaves the connection looking usable`() {
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)
    store.saveResult = CredentialSaveResult.StorageFailure

    val retry = authenticator().authenticate(null, unauthorized("access-1"))

    assertNull(retry)
    assertNull(store.tokens)
    assertEquals(1, refreshFailures)
  }

  /** A refresh that failed on the network is not proof the token is bad, so nothing is thrown away. */
  @Test
  fun `a refresh that could not be made keeps the tokens`() {
    store.tokens = OuraTokens("access-1", "refresh-1", OuraTokens.UNKNOWN_EXPIRY)
    val offline =
      OuraAuthenticator(
        store = store,
        service =
          OuraAuthService(
            credentials = { OuraCredentials("client-abc", "secret-xyz") },
            tokenUrl = "http://127.0.0.1:1/oauth/token",
          ),
        onRefreshFailed = { refreshFailures++ },
      )

    val retry = offline.authenticate(null, unauthorized("access-1"))

    assertNull(retry)
    assertEquals("refresh-1", store.tokens!!.refreshToken)
    assertEquals(0, refreshFailures)
  }
}
