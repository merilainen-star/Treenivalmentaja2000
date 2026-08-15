package fi.merilainen.treenivalmentaja.data.strava

/**
 * Where the Strava access token comes from. Same contract as
 * [fi.merilainen.treenivalmentaja.data.oura.OuraTokenSource]: the client knows nothing else about
 * authentication.
 */
fun interface StravaTokenSource {

  /** `null` when the user has never connected Strava, or has disconnected it. */
  suspend fun accessToken(): String?
}

/**
 * Anything that stopped a Strava request from producing data.
 *
 * Same contract as [fi.merilainen.treenivalmentaja.data.oura.OuraException]: [canRetry] separates
 * "try again in a moment" from a dead end, and [message] is Finnish because it is shown as written.
 */
sealed class StravaException(message: String, val canRetry: Boolean) : Exception(message)

/** No token has ever been stored. The answer is to connect Strava, not to retry. */
class StravaNotConnectedException :
  StravaException("Stravaa ei ole yhdistetty.", canRetry = false)

/** `401` after the authenticator has had its chance — the connection needs to be made again. */
class StravaAuthException :
  StravaException("Strava-yhteys on vanhentunut. Yhdistä Strava uudelleen.", canRetry = false)

/**
 * `429`. Strava's published limits are 200 requests per 15 minutes and 2 000 per day for a fresh
 * application; this app's sync spends a handful, so meeting this means something else went wrong
 * or another client shares the application. Waiting is the right response either way.
 */
class StravaRateLimitException :
  StravaException("Strava-tietoja haettiin liian tiheästi. Yritä hetken päästä.", canRetry = true)

/** `400`, `403` or `422` — ours to fix, not the user's to retry. */
class StravaRequestException(code: Int) :
  StravaException("Strava hylkäsi pyynnön (HTTP $code).", canRetry = false)

/** No network, a 5xx, or a body that could not be read as the documented JSON. */
class StravaUnavailableException(message: String) : StravaException(message, canRetry = true)

/**
 * The token endpoint rejected an authorization code or a refresh token. The only way forward is
 * connecting again from the start.
 */
class StravaAuthorizationException(message: String) : StravaException(message, canRetry = false)

/** No Strava client credentials yet. Fixed from Settings, not by retrying. */
class StravaNotConfiguredException :
  StravaException("Strava-tunnuksia ei ole vielä annettu.", canRetry = false)
