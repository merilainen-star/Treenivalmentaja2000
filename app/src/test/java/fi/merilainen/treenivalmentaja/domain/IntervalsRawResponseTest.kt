package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pretty printer, and the one property that makes it fit for this job: **it only ever inserts
 * whitespace**.
 *
 * The obvious implementation — `JSONObject(body).toString(2)` — is the wrong one here, and most of
 * these tests exist to pin the reasons. Re-parsing reorders keys, can reformat a number, and drops
 * a duplicate key silently. Every one of those would corrupt the thing this screen exists to show,
 * and the corruption would look exactly like a finding about intervals.icu.
 */
class IntervalsRawResponseTest {

  @Test
  fun `objects and arrays are indented`() {
    val pretty = prettyPrintJson("""{"a":1,"b":[2,3]}""")

    assertEquals(
      """
      {
        "a": 1,
        "b": [
          2,
          3
        ]
      }
      """
        .trimIndent(),
      pretty,
    )
  }

  /**
   * The number-format test. `3075.5` must not become `3075.50` or `3.0755E3`, and a big integer
   * must not lose its last digits to a double — durations in seconds are exactly the kind of value
   * being hunted for here.
   */
  @Test
  fun `numbers keep the digits the server sent`() {
    val pretty = prettyPrintJson("""{"a":3075.5,"b":3226,"c":1.0,"d":0.780,"e":9007199254740993}""")

    assertTrue(pretty, pretty.contains("\"a\": 3075.5"))
    assertTrue(pretty, pretty.contains("\"b\": 3226"))
    // A re-parse would print this as `1` and lose the fact that the service sent a decimal.
    assertTrue(pretty, pretty.contains("\"c\": 1.0"))
    assertTrue(pretty, pretty.contains("\"d\": 0.780"))
    // Beyond a double's exact range; a re-parse would round it.
    assertTrue(pretty, pretty.contains("\"e\": 9007199254740993"))
  }

  /** Key order is information — it is how the response is read against the API docs. */
  @Test
  fun `key order is preserved`() {
    val pretty = prettyPrintJson("""{"zebra":1,"apple":2,"moving_time":3}""")

    assertTrue(pretty, pretty.indexOf("zebra") < pretty.indexOf("apple"))
    assertTrue(pretty, pretty.indexOf("apple") < pretty.indexOf("moving_time"))
  }

  /** Nulls are data. "The service sent null" and "the service omitted it" are different findings. */
  @Test
  fun `nulls survive`() {
    assertTrue(prettyPrintJson("""{"a":null}""").contains("\"a\": null"))
  }

  /** Structural characters inside a string are text, not structure. */
  @Test
  fun `braces and commas inside strings are left alone`() {
    val pretty = prettyPrintJson("""{"name":"Aamulenkki {1, 2}: hyvä"}""")

    assertTrue(pretty, pretty.contains("""            "name": "Aamulenkki {1, 2}: hyvä"""".trim()))
    // One line for the value, so nothing was broken up inside the quotes.
    assertEquals(1, pretty.lines().count { it.contains("Aamulenkki") })
  }

  /** An escaped quote does not end the string, which is the whole reason the scanner tracks it. */
  @Test
  fun `escaped quotes do not end a string`() {
    val pretty = prettyPrintJson("""{"a":"say \"hi\", ok","b":2}""")

    assertTrue(pretty, pretty.contains("""say \"hi\", ok"""))
    assertTrue(pretty, pretty.contains("\"b\": 2"))
  }

  /** A trailing backslash before the closing quote must not swallow it. */
  @Test
  fun `an escaped backslash still ends the string`() {
    val pretty = prettyPrintJson("""{"a":"c:\\","b":2}""")

    assertTrue(pretty, pretty.contains("\"b\": 2"))
  }

  @Test
  fun `unicode escapes and non-ascii text pass through`() {
    val pretty = prettyPrintJson("""{"a":"m\u00e4ki","b":"mäki"}""")

    assertTrue(pretty, pretty.contains("""m\u00e4ki"""))
    assertTrue(pretty, pretty.contains("mäki"))
  }

  /**
   * Not JSON at all — a proxy's error page, say. It comes back unchanged rather than mangled or
   * rejected, because on a diagnostics screen an unexpected body is the finding.
   */
  @Test
  fun `a non-JSON body is not destroyed`() {
    val html = "<html>maintenance</html>"

    assertTrue(prettyPrintJson(html).contains("maintenance"))
  }

  @Test
  fun `an empty body stays empty`() {
    assertEquals("", prettyPrintJson(""))
  }

  @Test
  fun `an empty array is still an array`() {
    assertTrue(prettyPrintJson("[]").contains("["))
  }

  /** Every character the server sent, ignoring the whitespace this inserts, is still there. */
  @Test
  fun `nothing but whitespace is added or removed`() {
    val raw =
      """[{"id":"i1","moving_time":3226,"elapsed_time":3751,"icu_distance":9520.0,"x":null}]"""

    val strippedOriginal = raw.filterNot { it.isWhitespace() }
    val strippedPretty = prettyPrintJson(raw).filterNot { it.isWhitespace() }

    assertEquals(strippedOriginal, strippedPretty)
  }

  // ------------------------------------------------------------------ the response wrapper

  @Test
  fun `size is measured in bytes, not characters`() {
    // "ä" is two bytes as UTF-8, so a character count would under-report.
    val response = response(body = """{"a":"ä"}""")

    assertEquals(response.body.toByteArray(Charsets.UTF_8).size, response.byteSize)
    assertTrue(response.byteSize > response.body.length)
  }

  @Test
  fun `a 401 is not a success but still carries its body`() {
    val response = response(status = 401, body = """{"message":"denied"}""")

    assertFalse(response.isSuccess)
    assertTrue(response.prettyBody.contains("denied"))
  }

  /**
   * The credential test. The endpoint line is built from path and query, neither of which can
   * carry the key — it travels in a header the client attaches and records nowhere.
   */
  @Test
  fun `the endpoint line carries no credential`() {
    val response = response()

    assertFalse(response.endpoint, response.endpoint.contains("Authorization", ignoreCase = true))
    assertFalse(response.endpoint, response.endpoint.contains("Basic", ignoreCase = true))
    assertFalse(response.endpoint, response.endpoint.contains("API_KEY"))
  }

  private fun response(
    status: Int = 200,
    body: String = """[{"id":"i1"}]""",
    endpoint: String = "GET /api/v1/athlete/0/activities?oldest=2026-08-08&newest=2026-08-15",
  ) =
    IntervalsRawResponse(
      endpoint = endpoint,
      status = status,
      body = body,
      fetchedAtUtc = 1_786_774_323_000L,
    )
}
