package fi.merilainen.treenivalmentaja.data.analysis

import fi.merilainen.treenivalmentaja.domain.AnalysisModel

/**
 * Where an analysis provider's API key comes from, asked each time rather than captured once — the
 * user types it into Settings, so the answer changes while the app is running.
 */
fun interface AnalysisApiKeySource {

  /** `null` when no key has been entered for this provider, or it has been cleared. */
  suspend fun apiKey(): String?
}

/**
 * One analysis provider, behind one method.
 *
 * Three implementations — Claude, ChatGPT, Gemini — and the interface is this small because the
 * differences between them are entirely inside it. Each takes the same Finnish prompt string and
 * returns the same Finnish prose; what varies is the endpoint, the auth header, where the answer
 * hides in the response, and which status codes mean what. None of that reaches the ViewModel.
 */
interface AnalysisClient {

  /**
   * One prompt in, one answer out.
   *
   * @param model must belong to this client's provider. The ViewModel picks the client from
   *   [AnalysisModel.provider], so a mismatch is a programming error rather than a user-facing one.
   * @return the model's text, trimmed and never empty.
   */
  suspend fun analyse(prompt: String, model: AnalysisModel): String
}

/**
 * Anything that stopped an analysis from being produced, whichever provider was asked.
 *
 * Shared across all three deliberately: three parallel exception hierarchies would mean the
 * ViewModel and the card knowing which provider failed in order to read a message, when the only
 * things they actually need are the Finnish text and whether waiting would help. Each client maps
 * its own provider's status codes onto these.
 *
 * [canRetry] separates "try again in a moment" from a dead end, and [message] is Finnish because it
 * is shown as written — the same contract as `OuraException` and `IntervalsException`.
 */
sealed class AnalysisException(message: String, val canRetry: Boolean) : Exception(message)

/** No API key has been entered for the selected provider. The answer is to paste one. */
class AnalysisNotConfiguredException(providerLabel: String) :
  AnalysisException("$providerLabel-avainta ei ole annettu. Aseta se Asetuksista.", canRetry = false)

/** The key was rejected — missing, malformed, revoked, or not permitted for this model. */
class AnalysisAuthException :
  AnalysisException("Avain ei kelpaa. Tarkista se Asetuksista.", canRetry = false)

/**
 * The selected model no longer exists.
 *
 * Reachable precisely because the model list is a constant in this app rather than something
 * fetched: a model that is retired rather than merely superseded answers `404`, and a generic
 * "HTTP 404" would send the owner hunting for a broken key. This says which knob to turn.
 */
class AnalysisModelGoneException :
  AnalysisException("Valittua mallia ei enää ole. Valitse toinen malli Asetuksista.", canRetry = false)

/**
 * Rate limited.
 *
 * @param retryAfterSeconds from the `Retry-After` header when the provider sent one. `null` means
 *   it did not, and the caller waits on its own schedule rather than inventing a number and calling
 *   it the provider's.
 */
class AnalysisRateLimitException(val retryAfterSeconds: Long? = null) :
  AnalysisException(
    if (retryAfterSeconds != null) "Liikaa pyyntöjä. Odota $retryAfterSeconds s ja yritä uudelleen."
    else "Liikaa pyyntöjä tai kiintiö täynnä. Yritä hetken päästä.",
    canRetry = true,
  )

/** Overloaded. No quota was spent and nothing about the request was wrong; waiting is the remedy. */
class AnalysisOverloadedException :
  AnalysisException("Palvelu on ruuhkautunut. Yritä hetken päästä.", canRetry = true)

/** A malformed request — ours to fix, not the user's to retry. */
class AnalysisRequestException(code: Int) :
  AnalysisException("Pyyntö hylättiin (HTTP $code).", canRetry = false)

/**
 * A successful response the model declined to answer, or that a safety filter emptied.
 *
 * All three providers have a version of this and all three report it as a `200`: Claude sets
 * `stop_reason: "refusal"`, OpenAI `finish_reason: "content_filter"`, Gemini a `SAFETY`
 * `finishReason` or a `promptFeedback.blockReason` with no candidates at all. Nothing in a Finnish
 * training-analysis prompt should reach any of them, but a refusal carries no usable text, so it
 * has to be a state rather than a parse failure.
 */
class AnalysisRefusedException :
  AnalysisException("Malli ei vastannut tähän pyyntöön.", canRetry = false)

/** No network, a 5xx, or a body that could not be read as the documented JSON. */
class AnalysisUnavailableException(message: String) : AnalysisException(message, canRetry = true)

/** Shared wording, so three clients cannot drift into three phrasings of the same failure. */
internal object AnalysisMessages {

  const val OFFLINE = "AI-analyysi vaatii verkkoyhteyden."

  const val UNREADABLE = "Vastausta ei voitu lukea."

  const val EMPTY = "Vastaus tuli tyhjänä."
}
