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
 * The provider against payloads captured from the live service on 2026-08-09, and against a
 * throwaway local server for the statuses.
 *
 * The bodies under `src/test/resources/guide/` are real responses, not hand-written ones. A Moshi
 * mismatch in this project has shipped before, compiling cleanly and failing on the phone, and a
 * body written to match the parser cannot catch that.
 *
 * `com.sun.net.httpserver` rather than a mock-web-server dependency: this is four handlers, and
 * the JDK already ships one.
 */
class ExerciseDbProviderTest {

  private lateinit var server: HttpServer
  private lateinit var provider: ExerciseDbProvider

  /** Path -> (status, body). Replaced per test. */
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
    provider = ExerciseDbProvider(baseUrl = "http://127.0.0.1:${server.address.port}")
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
  fun `a real byId payload maps onto ExerciseGuide`() {
    val guide = ExerciseDbProvider.parseOne(fixture("exercise_byid.json"))

    assertEquals("EIeI8Vf", guide.id)
    assertEquals("barbell bench press", guide.name)
    assertEquals("https://static.exercisedb.dev/media/EIeI8Vf.gif", guide.imageUrl)
    assertEquals(listOf("pectorals"), guide.targetMuscles)
    assertEquals(listOf("barbell"), guide.equipment)
    assertEquals(7, guide.instructions.size)
  }

  /** The free tier requires crediting AscendAPI, and the sheet shows what the guide carries. */
  @Test
  fun `every guide carries the source's credit line`() {
    val guide = ExerciseDbProvider.parseOne(fixture("exercise_byid.json"))

    assertEquals("Liiketiedot: ExerciseDB / AscendAPI", guide.attribution)
  }

  /** The source numbers its own steps and the sheet numbers them again. Once is enough. */
  @Test
  fun `the source's own step numbering is stripped`() {
    val guide = ExerciseDbProvider.parseOne(fixture("exercise_byid.json"))

    assertFalse(guide.instructions.any { it.startsWith("Step:") })
    assertTrue(guide.instructions.first().startsWith("Lie flat on a bench"))
  }

  @Test
  fun `a real list payload maps every row`() {
    val guides = ExerciseDbProvider.parseMany(fixture("exercise_by_name.json"))

    assertEquals(5, guides.size)
    assertEquals("bodyweight incline side plank", guides.first().name)
  }

  /** The Cloudflare worker answers `error code: 1102`, which is not JSON at all. */
  @Test
  fun `a non-JSON body is a readable failure, not a crash`() {
    val failure = runCatching { ExerciseDbProvider.parseOne("error code: 1102") }.exceptionOrNull()

    assertTrue(failure is GuideUnavailableException)
  }

  @Test
  fun `an error envelope carries no exercise and is reported as unreadable`() {
    val failure =
      runCatching { ExerciseDbProvider.parseOne(fixture("exercise_not_found.json")) }
        .exceptionOrNull()

    assertTrue(failure is GuideUnavailableException)
  }

  // ------------------------------------------------------------------ relevance

  /**
   * The service's fuzzy matching does not miss, it invents: `name=cat cow` really does answer
   * with "cable squat row". Everything it returns has to earn its place by sharing whole words.
   */
  @Test
  fun `matches sharing no word with the query are dropped`() {
    val candidates = listOf(guide("cable squat row"), guide("band squat row"))

    assertEquals(emptyList<ExerciseGuide>(), ExerciseDbProvider.relevantTo("cat cow", candidates))
  }

  @Test
  fun `a match must contain every word of the query`() {
    val candidates =
      listOf(
        guide("ez bar standing french press"),
        guide("barbell wide reverse grip bench press horizontal"),
      )

    val kept = ExerciseDbProvider.relevantTo("bench press", candidates)

    assertEquals(listOf("barbell wide reverse grip bench press horizontal"), kept.map { it.name })
  }

  /** "planche" is not "plank", and the plain movement should come before its variants. */
  @Test
  fun `the shortest matching name comes first`() {
    val guides = ExerciseDbProvider.parseMany(fixture("exercise_by_name.json"))

    val kept = ExerciseDbProvider.relevantTo("plank", guides).map { it.name }

    assertEquals(
      listOf(
        "front plank with twist",
        "bodyweight incline side plank",
        "kneeling plank tap shoulder (male)",
      ),
      kept,
    )
  }

  /** A Finnish name has nothing to match, and inventing an answer is worse than admitting it. */
  @Test
  fun `a Finnish name matches nothing`() {
    val guides = ExerciseDbProvider.parseMany(fixture("exercise_by_name.json"))

    assertEquals(emptyList<ExerciseGuide>(), ExerciseDbProvider.relevantTo("Kissa-lehmä", guides))
  }

  // ------------------------------------------------------------------ statuses

  @Test
  fun `byId returns the guide on 200`() = runTest {
    routes = mapOf("/exercises/EIeI8Vf" to (200 to fixture("exercise_byid.json")))

    assertEquals("barbell bench press", provider.byId("EIeI8Vf").name)
  }

  /** A reference the provider does not have is a dead end, not something to retry. */
  @Test
  fun `byId turns 404 into not found`() = runTest {
    routes = mapOf("/exercises/zzzzzzz" to (404 to NOT_FOUND_BODY))

    val failure = runCatching { provider.byId("zzzzzzz") }.exceptionOrNull()

    assertTrue(failure is GuideNotFoundException)
  }

  @Test
  fun `a rate limit says so in Finnish and may be retried`() = runTest {
    routes = mapOf("/exercises/EIeI8Vf" to (429 to "rate limited"))

    val failure = runCatching { provider.byId("EIeI8Vf") }.exceptionOrNull()

    val unavailable = failure as GuideUnavailableException
    assertTrue(unavailable.message!!.contains("liian tiheästi"))
    assertTrue(unavailable.canRetry)
  }

  /** Cloudflare's 503 body is plain text; the status is read before the body is trusted. */
  @Test
  fun `a 503 is reported by its status rather than its body`() = runTest {
    routes = mapOf("/exercises/EIeI8Vf" to (503 to "error code: 1102"))

    val failure = runCatching { provider.byId("EIeI8Vf") }.exceptionOrNull()

    assertTrue((failure as GuideUnavailableException).message!!.contains("503"))
  }

  @Test
  fun `search filters the service's fuzzy answers before returning them`() = runTest {
    routes = mapOf("/exercises" to (200 to fixture("exercise_by_name.json")))

    val matches = provider.search("plank")

    assertEquals(3, matches.size)
    assertTrue(matches.all { it.name.contains("plank") })
  }

  @Test
  fun `search on an unreachable host is a network failure, not a crash`() = runTest {
    val offline = ExerciseDbProvider(baseUrl = "http://127.0.0.1:1")

    val failure = runCatching { offline.search("plank") }.exceptionOrNull()

    val unavailable = failure as GuideUnavailableException
    assertTrue(unavailable.message!!.contains("verkkoyhteyden"))
    assertTrue(unavailable.canRetry)
  }

  private fun guide(name: String) =
    ExerciseGuide(
      id = name.hashCode().toString(),
      name = name,
      imageUrl = "",
      instructions = emptyList(),
      targetMuscles = emptyList(),
      equipment = emptyList(),
      attribution = "Liiketiedot: ExerciseDB / AscendAPI",
    )

  private companion object {
    const val NOT_FOUND_BODY = """{"error":{"code":"NOT_FOUND","message":"not found"}}"""
  }
}
