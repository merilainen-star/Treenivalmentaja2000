package fi.merilainen.treenivalmentaja.data.intervals

/**
 * Where the intervals.icu API key comes from, asked each time rather than captured once — the user
 * types it into Settings, so the answer changes while the app is running.
 */
fun interface IntervalsApiKeySource {

  /** `null` when no key has been entered, or it has been cleared. */
  suspend fun apiKey(): String?
}

/**
 * Anything that stopped an intervals.icu request from producing data.
 *
 * Same contract as [fi.merilainen.treenivalmentaja.data.oura.OuraException]: [canRetry] separates
 * "try again in a moment" from a dead end, and [message] is Finnish because it is shown as written.
 */
sealed class IntervalsException(message: String, val canRetry: Boolean) : Exception(message)

/** No API key has been entered. The answer is to paste one, not to retry. */
class IntervalsNotConfiguredException :
  IntervalsException("Intervals.icu-avainta ei ole annettu.", canRetry = false)

/**
 * `401` — measured against the real service with both no credentials and a wrong key; it answers
 * `401` in each case rather than distinguishing them, so this message has to cover both.
 */
class IntervalsAuthException :
  IntervalsException(
    "Intervals.icu ei hyväksynyt avainta. Tarkista se asetuksista.",
    canRetry = false,
  )

/** `403` — the key is valid but not for this athlete or this endpoint. */
class IntervalsForbiddenException :
  IntervalsException("Intervals.icu epäsi pääsyn näihin tietoihin.", canRetry = false)

/**
 * `429`.
 *
 * @param retryAfterSeconds from the `Retry-After` header when the service sent one. `null` means it
 *   did not, and the caller waits on its own schedule rather than inventing a number and calling it
 *   the service's.
 */
class IntervalsRateLimitException(val retryAfterSeconds: Long? = null) :
  IntervalsException(
    if (retryAfterSeconds != null)
      "Intervals.icu pyytää odottamaan $retryAfterSeconds s ennen seuraavaa hakua."
    else "Intervals.icu-tietoja haettiin liian tiheästi. Yritä hetken päästä.",
    canRetry = true,
  )

/** `400` or `422` — ours to fix, not the user's to retry. */
class IntervalsRequestException(code: Int) :
  IntervalsException("Intervals.icu hylkäsi pyynnön (HTTP $code).", canRetry = false)

/** No network, a 5xx, or a body that could not be read as the documented JSON. */
class IntervalsUnavailableException(message: String) : IntervalsException(message, canRetry = true)
