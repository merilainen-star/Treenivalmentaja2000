package fi.merilainen.treenivalmentaja.data.guide

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * wger against payloads captured from the live API on 2026-08-09.
 *
 * Two fixtures on purpose: `458` (Plank) has nine pictures, `580` (Side Plank) has none. Two
 * thirds of wger's movements are like the second one, so the empty case is the normal one and has
 * to keep the sheet usable rather than blank it.
 */
class WgerProviderTest {

  private lateinit var server: HttpServer
  private lateinit var provider: WgerProvider

  private var routes: Map<String, Pair<Int, String>> = emptyMap()

  @Before
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange: HttpExchange ->
      val (status, body) = routes[exchange.requestURI.path] ?: (404 to NOT_FOUND_BODY)
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    provider = WgerProvider(baseUrl = "http://127.0.0.1:${server.address.port}")
  }

  @After
  fun stop() {
    server.stop(0)
  }

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/guide/$name")) { "missing fixture $name" }
      .bufferedReader()
      .use { it.readText() }

  // ------------------------------------------------------------------ parsing

  @Test
  fun `a real payload maps onto ExerciseGuide`() {
    val guide = WgerProvider.parseExerciseInfo(fixture("wger_exerciseinfo_458.json"))

    assertEquals("458", guide.id)
    assertEquals("Plank", guide.name)
    assertTrue(guide.imageUrl.startsWith("https://wger.de/media/exercise-images/458/"))
    assertTrue(guide.instructions.isNotEmpty())
    assertEquals(listOf("none (bodyweight exercise)"), guide.equipment)
  }

  /** wger writes prose in HTML; the sheet numbers the steps itself. */
  @Test
  fun `HTML paragraphs become one instruction each`() {
    val lines =
      WgerProvider.htmlToLines("<p>First thing.</p>\n<p>Second thing.</p><ul><li>A point</li></ul>")

    assertEquals(listOf("First thing.", "Second thing.", "A point"), lines)
  }

  @Test
  fun `entities and stray markup are removed`() {
    val lines = WgerProvider.htmlToLines("<p>Keep <b>arms</b> &amp; legs straight<br>always</p>")

    assertEquals(listOf("Keep arms & legs straight", "always"), lines)
  }

  @Test
  fun `no description is an empty list, not a blank line`() {
    assertEquals(emptyList<String>(), WgerProvider.htmlToLines(null))
    assertEquals(emptyList<String>(), WgerProvider.htmlToLines("<p></p>"))
  }

  /** Two thirds of wger's movements have no picture. The instructions still make it worth opening. */
  @Test
  fun `a movement without a picture still produces a guide`() {
    val guide = WgerProvider.parseExerciseInfo(fixture("wger_exerciseinfo_580.json"))

    assertEquals("Side Plank", guide.name)
    assertEquals("", guide.imageUrl)
    assertTrue(guide.instructions.isNotEmpty())
  }

  @Test
  fun `a body that is not JSON is a readable failure`() {
    val failure =
      runCatching { WgerProvider.parseExerciseInfo("<html>gateway timeout</html>") }
        .exceptionOrNull()

    assertTrue(failure is GuideUnavailableException)
  }

  // ------------------------------------------------------------------ attribution

  /**
   * CC-BY-SA needs the licence and the author named, and wger's images each carry their own — so
   * two movements from this one source can owe credit to different people.
   */
  @Test
  fun `the credit line names the licence and the image's author`() {
    val guide = WgerProvider.parseExerciseInfo(fixture("wger_exerciseinfo_458.json"))

    assertTrue(guide.attribution.contains("wger.de"))
    assertTrue(guide.attribution.contains("CC-BY-SA"))
    assertTrue(guide.attribution.contains("kuva:"))
  }

  @Test
  fun `with no picture there is no image author to credit`() {
    val guide = WgerProvider.parseExerciseInfo(fixture("wger_exerciseinfo_580.json"))

    assertTrue(guide.attribution.contains("wger.de"))
    assertFalse(guide.attribution.contains("kuva:"))
  }

  @Test
  fun `an unknown licence still credits the source`() {
    assertEquals("Liiketiedot: wger.de", WgerProvider.attributionFor(null, null))
  }

  // ------------------------------------------------------------------ requests

  @Test
  fun `byId returns the guide on 200`() = runTest {
    routes = mapOf("/exerciseinfo/458/" to (200 to fixture("wger_exerciseinfo_458.json")))

    assertEquals("Plank", provider.byId("458").name)
  }

  @Test
  fun `byId turns 404 into not found`() = runTest {
    val failure = runCatching { provider.byId("99999") }.exceptionOrNull()

    assertTrue(failure is GuideNotFoundException)
  }

  /**
   * No request at all. wger removed `/exercise/search/` and `?name=` is a case-sensitive exact
   * match, so a Finnish name cannot hit it under any capitalisation — the fuzzy path belongs to
   * ExerciseDB. The unreachable base URL is the assertion: if this ever made a request it would
   * fail rather than quietly return empty.
   */
  @Test
  fun `search makes no request and finds nothing`() = runTest {
    val unreachable = WgerProvider(baseUrl = "http://127.0.0.1:1")

    assertEquals(emptyList<ExerciseGuide>(), unreachable.search("Lankku"))
  }

  private companion object {
    const val NOT_FOUND_BODY = """{"detail":"No Exercise matches the given query."}"""
  }
}
