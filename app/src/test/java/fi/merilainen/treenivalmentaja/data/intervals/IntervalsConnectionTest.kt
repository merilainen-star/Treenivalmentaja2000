package fi.merilainen.treenivalmentaja.data.intervals

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Storing a key, testing it, and giving it up.
 *
 * Far fewer states than the OAuth connection this replaced, which is the point of a personal API
 * key for a single-user app: there is no browser round trip to survive, no `state` to validate and
 * no refresh token to avoid spending twice.
 */
class IntervalsConnectionTest {

  private lateinit var server: HttpServer

  private var status = 200
  private var body = "[]"

  private val store = FakeIntervalsApiKeyStorage()
  private var clearedRows = 0

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/v1/athlete/0/activities") { exchange: HttpExchange ->
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
    IntervalsConnection(
      store = store,
      client =
        IntervalsClient(
          apiKeys = { store.apiKey() },
          baseUrl = "http://127.0.0.1:${server.address.port}",
        ),
      onKeyCleared = { clearedRows++ },
    )

  @Test
  fun `without a key there is nothing configured`() = runTest {
    val connection = connection()

    connection.refreshState()

    assertEquals(IntervalsConnectionState.NotConfigured, connection.state.value)
  }

  @Test
  fun `saving a key moves the card to configured`() = runTest {
    val connection = connection()

    assertTrue(connection.saveApiKey("abc123"))

    assertEquals(IntervalsConnectionState.Configured, connection.state.value)
    assertEquals("abc123", store.key)
  }

  @Test
  fun `a blank key is refused`() = runTest {
    val connection = connection()

    assertEquals(false, connection.saveApiKey("   "))
    assertNull(store.key)
  }

  /** Pasted values carry invisible whitespace; a trailing space must not become part of a key. */
  @Test
  fun `a key is trimmed`() = runTest {
    val connection = connection()

    connection.saveApiKey("  abc123  ")

    assertEquals("abc123", store.key)
  }

  @Test
  fun `a working key reports how many activities the test found`() = runTest {
    body = """[{"id":"i1"}]"""
    val connection = connection()
    connection.saveApiKey("abc123")

    connection.testKey()

    assertEquals(IntervalsConnectionState.Verified(1), connection.state.value)
  }

  /**
   * Zero is a success. An athlete with nothing logged in the last year still has a working key,
   * and reporting that as a failure would send them hunting for a problem that is not there.
   */
  @Test
  fun `a working key that finds nothing is still verified`() = runTest {
    body = "[]"
    val connection = connection()
    connection.saveApiKey("abc123")

    connection.testKey()

    assertEquals(IntervalsConnectionState.Verified(0), connection.state.value)
  }

  @Test
  fun `a rejected key reports the reason and keeps the key for editing`() = runTest {
    status = 401
    val connection = connection()
    connection.saveApiKey("wrong")

    connection.testKey()

    val state = connection.state.value
    assertTrue(state.toString(), state is IntervalsConnectionState.Failed)
    // Kept, not wiped: the user is about to correct a typo, not start over.
    assertEquals("wrong", store.key)
  }

  /** No network is a failure the user can act on by waiting, not by changing the key. */
  @Test
  fun `an unreachable service is a failure, not a bad key`() = runTest {
    val connection =
      IntervalsConnection(
        store = store,
        // A port nothing is listening on.
        client = IntervalsClient(apiKeys = { store.apiKey() }, baseUrl = "http://127.0.0.1:1"),
        onKeyCleared = { clearedRows++ },
      )
    connection.saveApiKey("abc123")

    connection.testKey()

    val state = connection.state.value as IntervalsConnectionState.Failed
    assertTrue(state.message, state.message.contains("verkkoyhteyden"))
    assertEquals("abc123", store.key)
  }

  @Test
  fun `testing without a key says so rather than calling the service`() = runTest {
    val connection = connection()

    connection.testKey()

    assertEquals(IntervalsConnectionState.NotConfigured, connection.state.value)
  }

  @Test
  fun `clearing the key drops the cached rows too`() = runTest {
    val connection = connection()
    connection.saveApiKey("abc123")

    connection.clearApiKey()

    assertNull(store.key)
    assertEquals(1, clearedRows)
    assertEquals(IntervalsConnectionState.NotConfigured, connection.state.value)
  }

  @Test
  fun `dismissing a failure returns to whether a key is stored`() = runTest {
    status = 401
    val connection = connection()
    connection.saveApiKey("abc123")
    connection.testKey()

    connection.dismissFailure()

    assertEquals(IntervalsConnectionState.Configured, connection.state.value)
  }
}
