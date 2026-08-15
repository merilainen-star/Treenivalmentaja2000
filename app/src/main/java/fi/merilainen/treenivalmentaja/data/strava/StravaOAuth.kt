package fi.merilainen.treenivalmentaja.data.strava

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * The parts of Strava's OAuth2 authorization-code flow that are pure text.
 *
 * Separated from anything Android for the same reason [fi.merilainen.treenivalmentaja.data.oura.OuraOAuth]
 * is: what the authorization URL says and whether a redirect may be acted on are decisions worth
 * testing without a device.
 *
 * **No PKCE, unlike Oura.** Strava's token endpoint does not accept a `code_verifier`; the client
 * secret is what authenticates the exchange, and Strava requires it on every token request. The
 * `state` parameter therefore carries the whole burden of tying a redirect to the request this
 * device actually made, and it is validated just as strictly.
 */
internal object StravaOAuth {

  /** The mobile variant; it can hand the login to the Strava app when one is installed. */
  const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"

  const val TOKEN_URL = "https://www.strava.com/oauth/token"

  /**
   * The host is `localhost` because Strava validates the redirect's *host* against the
   * "Authorization Callback Domain" field of the API application, whatever the scheme — and
   * `localhost` is the value that field accepts for an app with no web domain. The scheme is what
   * routes it back here; the manifest declares this exact host on `StravaCallbackActivity`.
   */
  const val REDIRECT_URI = "treenivalmentaja://localhost/strava"

  /**
   * `activity:read_all` and nothing else.
   *
   * `read_all` rather than `read` because runs are commonly private, and a scope that returns only
   * public activities would silently show an empty training history. Profile, segment and write
   * scopes are not requested: nothing here posts to Strava or reads anything but the user's own
   * activities.
   */
  const val SCOPES = "activity:read_all"

  /** The CSRF guard: generated here, sent to Strava, and compared with what comes back. */
  fun newState(random: SecureRandom = SecureRandom()): String = randomBase64Url(16, random)

  fun authorizationUrl(clientId: String, state: String): String =
    buildString {
      append(AUTHORIZE_URL)
      append("?response_type=code")
      append("&client_id=").append(encode(clientId))
      append("&redirect_uri=").append(encode(REDIRECT_URI))
      append("&scope=").append(encode(SCOPES))
      append("&state=").append(encode(state))
      append("&approval_prompt=auto")
    }

  /**
   * What an incoming redirect may be acted on as. Same contract as Oura's: the receiving activity
   * is exported, so the argument arrives from outside the app and is hostile until the `state`
   * matches. See `docs/SECURITY.md` § Exported Android Components.
   */
  sealed interface Redirect {

    /** Validated. [code] may be exchanged. [scope] is what Strava actually granted. */
    data class Code(val code: String, val scope: String?) : Redirect

    /** Strava said no, or the user did. `access_denied` is the ordinary case. */
    data class Denied(val error: String) : Redirect

    /** The `state` was missing or not the one we sent. Nothing is exchanged. */
    data object StateMismatch : Redirect

    /** Not our redirect URI at all, or carrying neither a code nor an error. */
    data object Unusable : Redirect
  }

  /**
   * @param expectedState the `state` generated when the authorization was started. `null` means no
   *   authorization is in flight, which makes any redirect a mismatch rather than something to
   *   trust.
   */
  fun readRedirect(uri: String?, expectedState: String?): Redirect {
    if (uri == null || !uri.startsWith("$REDIRECT_URI?")) return Redirect.Unusable
    val params = queryOf(uri.substringAfter('?'))
    if (expectedState.isNullOrEmpty() || params["state"] != expectedState) {
      return Redirect.StateMismatch
    }
    params["error"]?.takeIf { it.isNotBlank() }?.let { return Redirect.Denied(it) }
    val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Redirect.Unusable
    return Redirect.Code(code, params["scope"]?.takeIf { it.isNotBlank() })
  }

  private fun queryOf(query: String): Map<String, String> =
    query
      .split('&')
      .filter { it.isNotEmpty() }
      .mapNotNull { pair ->
        val name = pair.substringBefore('=', "")
        if (name.isEmpty()) return@mapNotNull null
        name to decode(pair.substringAfter('=', ""))
      }
      .toMap()

  private fun randomBase64Url(bytes: Int, random: SecureRandom): String =
    ENCODER.encodeToString(ByteArray(bytes).also { random.nextBytes(it) })

  private fun encode(raw: String): String = URLEncoder.encode(raw, "UTF-8")

  private fun decode(raw: String): String =
    try {
      URLDecoder.decode(raw, "UTF-8")
    } catch (e: IllegalArgumentException) {
      // A malformed percent-escape in something the browser handed us is not a crash.
      raw
    }

  private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
}
