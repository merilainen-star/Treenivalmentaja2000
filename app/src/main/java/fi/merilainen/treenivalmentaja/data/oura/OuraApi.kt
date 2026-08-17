package fi.merilainen.treenivalmentaja.data.oura

/**
 * The collections this app reads from Oura API V2.
 *
 * They are listed as one enum rather than modelled as four endpoints because in the specification
 * they *are* one endpoint with four item types: every collection answers
 * `?start_date&end_date&next_token` with `{data, next_token}`. See `docs/API_INTEGRATIONS.md` and
 * the vendored `docs/api/oura-openapi-1.37.json` it was read out of.
 *
 * `daily_activity` is here even though nothing on screen needs an activity score yet: it is the
 * third column of `OuraDailySummaryEntity`, and fetching it is one more line rather than one more
 * endpoint.
 */
internal enum class OuraCollection(val path: String) {
  DAILY_READINESS("daily_readiness"),
  DAILY_SLEEP("daily_sleep"),
  DAILY_ACTIVITY("daily_activity"),
  WORKOUT("workout"),

  /**
   * The **sleep periods**, not the sleep score — `sleep`, where [DAILY_SLEEP] is `daily_sleep`.
   *
   * Named `SLEEP_PERIODS` rather than `SLEEP` on purpose. It sits directly beside `DAILY_SLEEP` in
   * this enum, their paths differ only by a prefix, and they return entirely different documents:
   * one is a 0–100 score for the night, the other is the night itself with its HRV and heart rate.
   * Two constants that differ only by a prefix, next to each other, returning different things is a
   * mistake waiting to be made by whoever reads this next, so the name says which is which.
   *
   * Unlike the other four this collection returns **more than one document per day** — naps are
   * sleep periods too. Picking the right one is [OuraMappers]' job.
   */
  SLEEP_PERIODS("sleep"),
}

/**
 * Where the access token comes from.
 *
 * An interface, and the only thing the client knows about authentication. The OAuth2 flow that
 * fills it — authorization code, PKCE, `EncryptedSharedPreferences` — is the next milestone step
 * (`docs/AUTHENTICATION.md`); until it exists this returns `null` and every call fails as
 * [OuraNotConnectedException], which is the honest description of a build with no credentials.
 */
fun interface OuraTokenSource {

  /** `null` when the user has never connected Oura, or has disconnected it. */
  suspend fun accessToken(): String?
}

/**
 * Anything that stopped an Oura request from producing data.
 *
 * [canRetry] separates "try again in a moment" from a dead end, and [message] is in Finnish
 * because it is shown as written — the same contract as
 * [fi.merilainen.treenivalmentaja.data.guide.GuideUnavailableException].
 */
sealed class OuraException(message: String, val canRetry: Boolean) : Exception(message)

/** No token has ever been stored. The answer is to connect Oura, not to retry. */
class OuraNotConnectedException :
  OuraException("Ouraa ei ole yhdistetty.", canRetry = false)

/**
 * `401` — "access token is expired, malformed or revoked".
 *
 * Retryable only after a refresh, which is the `Authenticator`'s job once the OAuth flow exists.
 * From this client's point of view the call is over.
 */
class OuraAuthException :
  OuraException("Oura-yhteys on vanhentunut. Yhdistä Oura uudelleen.", canRetry = false)

/**
 * `403` — the odd one out among the error codes.
 *
 * The spec's wording is "the user's subscription to Oura has expired and their data is not
 * available via the API". Nothing is broken and nothing will change by asking again: the ring
 * still works, the API does not. A state to show, not an error to retry.
 */
class OuraSubscriptionExpiredException :
  OuraException(
    "Oura-tilaus on päättynyt, eikä tietoja saa enää rajapinnasta.",
    canRetry = false,
  )

/**
 * `429`.
 *
 * The specification documents no rate-limit numbers and no `Retry-After` header, so how long to
 * wait has to be chosen rather than read. This type only says that waiting is the right response.
 */
class OuraRateLimitException :
  OuraException("Oura-tietoja haettiin liian tiheästi. Yritä hetken päästä.", canRetry = true)

/**
 * `400` and `422` — a malformed request or a parameter Oura rejected.
 *
 * Ours to fix, not the user's to retry: the same request will be rejected the same way forever.
 */
class OuraRequestException(code: Int) :
  OuraException("Oura hylkäsi pyynnön (HTTP $code).", canRetry = false)

/** No network, a 5xx, or a body that could not be read as the specified JSON. */
class OuraUnavailableException(message: String) : OuraException(message, canRetry = true)

/**
 * The token endpoint rejected an authorization code or a refresh token.
 *
 * Distinct from [OuraAuthException], which is a *collection* request meeting a stale access token
 * and can be fixed by refreshing. This one means the refresh itself failed — a spent or revoked
 * refresh token, a mismatched `code_verifier`, wrong client credentials — and the only way forward
 * is connecting again from the start.
 */
class OuraAuthorizationException(message: String) : OuraException(message, canRetry = false)

/**
 * No Oura client credentials yet.
 *
 * The ordinary state of a fresh install, and something the user fixes from inside the app: Settings
 * asks for the Client ID and Secret of an application registered in Oura's developer portal
 * (ADR-009 in `docs/DECISIONS.md`). This is thrown rather than a browser opened, because sending
 * Oura an empty `client_id` produces a confusing error page instead of a clear state.
 */
class OuraNotConfiguredException :
  OuraException("Oura-tunnuksia ei ole vielä annettu.", canRetry = false)
