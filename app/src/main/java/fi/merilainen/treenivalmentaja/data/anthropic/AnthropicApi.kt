package fi.merilainen.treenivalmentaja.data.anthropic

/**
 * Where the Anthropic API key comes from, asked each time rather than captured once — the user types
 * it into Settings, so the answer changes while the app is running.
 *
 * The same shape as [fi.merilainen.treenivalmentaja.data.intervals.IntervalsApiKeySource], and for
 * the same reason.
 */
fun interface AnthropicApiKeySource {

  /** `null` when no key has been entered, or it has been cleared. */
  suspend fun apiKey(): String?
}

/**
 * Anything that stopped an analysis from being produced.
 *
 * Same contract as [fi.merilainen.treenivalmentaja.data.intervals.IntervalsException]: [canRetry]
 * separates "try again in a moment" from a dead end, and [message] is Finnish because it is shown as
 * written.
 */
sealed class AnthropicException(message: String, val canRetry: Boolean) : Exception(message)

/** No API key has been entered. The answer is to paste one, not to retry. */
class AnthropicNotConfiguredException :
  AnthropicException("Anthropic-avainta ei ole annettu. Aseta se Asetuksista.", canRetry = false)

/** `401` — the key is missing, malformed or revoked. */
class AnthropicAuthException :
  AnthropicException("Avain ei kelpaa. Tarkista se Asetuksista.", canRetry = false)

/**
 * `404` — the selected model no longer exists.
 *
 * Reachable because the model list is a constant in this app rather than something fetched: a model
 * that is retired rather than merely superseded answers `404`, and a generic "HTTP 404" would send
 * the owner hunting for a broken key. This says which knob to turn instead.
 */
class AnthropicModelGoneException :
  AnthropicException("Valittua mallia ei enää ole. Valitse toinen malli Asetuksista.", canRetry = false)

/**
 * `429`.
 *
 * @param retryAfterSeconds from the `Retry-After` header when the service sent one. `null` means it
 *   did not, and the caller waits on its own schedule rather than inventing a number and calling it
 *   the service's.
 */
class AnthropicRateLimitException(val retryAfterSeconds: Long? = null) :
  AnthropicException(
    if (retryAfterSeconds != null) "Liikaa pyyntöjä. Odota $retryAfterSeconds s ja yritä uudelleen."
    else "Liikaa pyyntöjä. Yritä hetken päästä.",
    canRetry = true,
  )

/**
 * `529` — overloaded.
 *
 * Distinct from [AnthropicRateLimitException] on purpose: no quota was spent, nothing about the
 * request was wrong, and waiting is the whole remedy.
 */
class AnthropicOverloadedException :
  AnthropicException("Palvelu on ruuhkautunut. Yritä hetken päästä.", canRetry = true)

/** `400` or `422` — ours to fix, not the user's to retry. */
class AnthropicRequestException(code: Int) :
  AnthropicException("Pyyntö hylättiin (HTTP $code).", canRetry = false)

/**
 * A `200` the model declined to answer.
 *
 * Nothing in a Finnish training-analysis prompt should reach the safety classifiers, but a refusal
 * is a successful HTTP response carrying no usable text, so it has to be a state rather than a
 * parse failure.
 */
class AnthropicRefusedException :
  AnthropicException("Malli ei vastannut tähän pyyntöön.", canRetry = false)

/** No network, a 5xx, or a body that could not be read as the documented JSON. */
class AnthropicUnavailableException(message: String) : AnthropicException(message, canRetry = true)
