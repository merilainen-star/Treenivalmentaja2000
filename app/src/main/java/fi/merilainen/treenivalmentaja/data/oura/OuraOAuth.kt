package fi.merilainen.treenivalmentaja.data.oura

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The parts of the OAuth2 authorization-code flow that are pure text.
 *
 * Separated from anything Android so the interesting decisions — what the authorization URL says,
 * whether a redirect may be acted on — can be tested without a device. See
 * `docs/AUTHENTICATION.md`; the URLs come from the vendored specification, not from memory.
 */
internal object OuraOAuth {

  /** Note the host: authorization is on `cloud.`, the token exchange on `api.`. */
  const val AUTHORIZE_URL = "https://cloud.ouraring.com/oauth/authorize"

  const val TOKEN_URL = "https://api.ouraring.com/oauth/token"

  /** Registered in the Oura developer console and in the manifest. Both must match exactly. */
  const val REDIRECT_URI = "treenivalmentaja://oauth2callback"

  /**
   * Only what the app actually reads.
   *
   * `daily` covers readiness, sleep and activity; `workout` covers completed workouts. `personal`
   * is not requested — nothing shows the profile, and asking for a scope in order to have it is
   * how an app ends up holding data it has no use for.
   */
  const val SCOPES = "daily workout"

  /**
   * 32 random bytes, base64url — 43 characters, inside the 43..128 the RFC allows.
   *
   * The verifier never leaves the device until the token exchange, and the challenge derived from
   * it is what travels through the browser. That is the whole point: an authorization code
   * intercepted on the way back is useless without this string.
   */
  fun newCodeVerifier(random: SecureRandom = SecureRandom()): String = randomBase64Url(32, random)

  /** S256: the base64url of the SHA-256 of the verifier's ASCII bytes, unpadded. */
  fun codeChallengeOf(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return ENCODER.encodeToString(digest)
  }

  /** The CSRF guard: generated here, sent to Oura, and compared with what comes back. */
  fun newState(random: SecureRandom = SecureRandom()): String = randomBase64Url(16, random)

  fun authorizationUrl(clientId: String, codeChallenge: String, state: String): String =
    buildString {
      append(AUTHORIZE_URL)
      append("?response_type=code")
      append("&client_id=").append(encode(clientId))
      append("&redirect_uri=").append(encode(REDIRECT_URI))
      append("&scope=").append(encode(SCOPES))
      append("&state=").append(encode(state))
      append("&code_challenge=").append(encode(codeChallenge))
      append("&code_challenge_method=S256")
    }

  /**
   * What an incoming redirect may be acted on as.
   *
   * The manifest exports the activity that receives these, so the argument arrives from outside the
   * app and is treated as such: anything that is not our redirect, or does not carry the exact
   * `state` this device generated, yields nothing an exchange can be attempted with. See
   * `docs/SECURITY.md` § Exported Android Components.
   */
  sealed interface Redirect {

    /** Validated. [code] may be exchanged. */
    data class Code(val code: String) : Redirect

    /** Oura said no, or the user did. `error=access_denied` is the ordinary case. */
    data class Denied(val error: String) : Redirect

    /**
     * The `state` was missing, or was not the one we sent.
     *
     * Aborts the flow. Nothing is exchanged, because this is what a forged redirect looks like.
     */
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
    // Checked before the code is even looked at: a redirect whose state does not match is not a
    // failed login, it is someone else's request arriving at our activity.
    if (expectedState.isNullOrEmpty() || params["state"] != expectedState) {
      return Redirect.StateMismatch
    }
    params["error"]?.takeIf { it.isNotBlank() }?.let { return Redirect.Denied(it) }
    val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Redirect.Unusable
    return Redirect.Code(code)
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

  /** Unpadded: `=` is not allowed in a `code_challenge`. */
  private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
}
