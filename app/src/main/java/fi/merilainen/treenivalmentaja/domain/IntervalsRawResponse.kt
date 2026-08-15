package fi.merilainen.treenivalmentaja.domain

/**
 * One HTTP response from intervals.icu, kept as it arrived.
 *
 * The whole point of this type is that [body] is **the bytes the server sent**, decoded as text and
 * never round-tripped through a DTO. A response parsed into `IntervalsActivityDto` and serialised
 * again would answer a different question — "what does this app understand" rather than "what does
 * intervals.icu send" — and the second is the one worth asking when a field is missing or a
 * duration does not match the watch.
 *
 * @param endpoint the request line, for display. **Never carries the `Authorization` header**, and
 *   never the API key: see `IntervalsClient.fetchRaw`.
 * @param status the HTTP status, whatever it was. A `401` is as interesting as a `200` here.
 * @param body the response body verbatim, or the empty string when there was none.
 * @param fetchedAtUtc when this was fetched, epoch millis.
 */
data class IntervalsRawResponse(
  val endpoint: String,
  val status: Int,
  val body: String,
  val fetchedAtUtc: Long,
) {

  /** Bytes as UTF-8, which is what "how big is this response" means. */
  val byteSize: Int
    get() = body.toByteArray(Charsets.UTF_8).size

  val isSuccess: Boolean
    get() = status in 200..299

  /**
   * [body] with newlines and indentation inserted, and **nothing else changed**.
   *
   * Deliberately not `JSONObject(body).toString(2)`, which is the obvious way and the wrong one: it
   * re-parses into a map and prints that back, which reorders keys, can turn `1.0` into `1` or
   * `3075.5` into something with a different precision, and silently drops a duplicate key. Every
   * one of those would corrupt the very thing this screen exists to show.
   *
   * This walks the text and only ever *inserts whitespace between tokens*. String contents,
   * including escapes, are copied through untouched. A body that is not JSON at all — an HTML
   * error page, say — comes back unchanged rather than mangled or rejected.
   */
  val prettyBody: String
    get() = prettyPrintJson(body)
}

/**
 * Just enough of an activity to name it in the diagnostics picker.
 *
 * Not a training type and not meant to become one — the id is what the raw fetch needs, and the
 * rest is only there so a person can tell one row from another.
 */
data class IntervalsActivityRef(
  val id: String,
  val startTimeUtc: Long,
  val sportType: String,
  val distanceMeters: Double? = null,
)

/**
 * Inserts newlines and indentation into JSON text without re-parsing it.
 *
 * Structural characters (`{`, `}`, `[`, `]`, `,`, `:`) outside strings get whitespace around them;
 * everything else is copied verbatim. Because no value is ever parsed, no value can be reformatted:
 * a number reaches the screen with exactly the digits the server sent it with.
 */
internal fun prettyPrintJson(raw: String): String {
  if (raw.isBlank()) return raw
  val out = StringBuilder(raw.length + raw.length / 4)
  var depth = 0
  var inString = false
  var escaped = false

  fun newline(at: Int) {
    out.append('\n')
    repeat(at) { out.append("  ") }
  }

  for (ch in raw) {
    if (inString) {
      out.append(ch)
      // A backslash escapes the next character, so a `\"` does not end the string. Tracking this
      // is the whole reason the loop cannot simply look for quotes.
      escaped = if (escaped) false else ch == '\\'
      if (ch == '"' && !escaped) inString = false
      continue
    }
    when (ch) {
      '"' -> {
        inString = true
        out.append(ch)
      }
      '{', '[' -> {
        out.append(ch)
        depth++
        newline(depth)
      }
      '}', ']' -> {
        depth--
        newline(depth)
        out.append(ch)
      }
      ',' -> {
        out.append(ch)
        newline(depth)
      }
      ':' -> out.append(": ")
      // Whitespace the server sent between tokens is dropped, because this is re-inserting its
      // own. Whitespace *inside* a string never reaches here — that is handled above.
      ' ', '\n', '\r', '\t' -> Unit
      else -> out.append(ch)
    }
  }
  return out.toString()
}
