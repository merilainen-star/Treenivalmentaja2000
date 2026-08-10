package fi.merilainen.treenivalmentaja.data.oura

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PKCE, the authorization URL, and what a redirect is allowed to mean.
 *
 * The last of those is the security-relevant half of this file. The activity that receives a
 * redirect is exported, so anything on the device can start it with any URI at all, and the only
 * thing standing between that and a token exchange is [OuraOAuth.readRedirect].
 */
class OuraOAuthTest {

  // ------------------------------------------------------------------ PKCE

  /** RFC 7636 allows 43..128 characters; 32 random bytes as unpadded base64url is 43. */
  @Test
  fun `a verifier is long enough and carries no padding`() {
    val verifier = OuraOAuth.newCodeVerifier()

    assertEquals(43, verifier.length)
    assertFalse(verifier.contains("="))
    assertFalse(verifier.contains("+"))
    assertFalse(verifier.contains("/"))
  }

  @Test
  fun `two verifiers are not the same`() {
    assertNotEquals(OuraOAuth.newCodeVerifier(), OuraOAuth.newCodeVerifier())
  }

  /**
   * Computed here from the definition rather than compared against a value this code produced, so
   * the test would notice the challenge quietly becoming something other than S256.
   */
  @Test
  fun `the challenge is the unpadded base64url SHA-256 of the verifier`() {
    val verifier = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

    val expected =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    assertEquals(expected, OuraOAuth.codeChallengeOf(verifier))
  }

  /** The verifier itself must never travel through the browser — only its hash. */
  @Test
  fun `the challenge is not the verifier`() {
    val verifier = OuraOAuth.newCodeVerifier()

    assertNotEquals(verifier, OuraOAuth.codeChallengeOf(verifier))
  }

  // ------------------------------------------------------------------ the authorization URL

  @Test
  fun `the authorization url carries everything Oura needs and nothing else`() {
    val url = OuraOAuth.authorizationUrl("client-123", "challenge-abc", "state-xyz")

    assertTrue(url, url.startsWith("https://cloud.ouraring.com/oauth/authorize?"))
    assertTrue(url, url.contains("response_type=code"))
    assertTrue(url, url.contains("client_id=client-123"))
    assertTrue(url, url.contains("code_challenge=challenge-abc"))
    assertTrue(url, url.contains("code_challenge_method=S256"))
    assertTrue(url, url.contains("state=state-xyz"))
  }

  /**
   * Only the three scopes the app reads. Asking for more is data it would then be holding.
   *
   * `heartrate` is here because Oura puts no heart rate on a workout — the average and maximum
   * shown on a finished session come from the time series and cannot come from anywhere else. The
   * five that are absent are absent on purpose, and the privacy policy says so in public.
   */
  @Test
  fun `only daily, workout and heartrate scopes are requested`() {
    val url = OuraOAuth.authorizationUrl("c", "ch", "s")

    assertTrue(url, url.contains("scope=daily+workout+heartrate"))
    assertFalse(url, url.contains("personal"))
    assertFalse(url, url.contains("email"))
    assertFalse(url, url.contains("spo2"))
    assertFalse(url, url.contains("tag"))
  }

  @Test
  fun `the redirect uri is escaped, not pasted in raw`() {
    val url = OuraOAuth.authorizationUrl("c", "ch", "s")

    assertTrue(url, url.contains("redirect_uri=treenivalmentaja%3A%2F%2Foauth2callback"))
  }

  // ------------------------------------------------------------------ reading a redirect

  @Test
  fun `a matching state yields the code`() {
    val redirect =
      OuraOAuth.readRedirect("treenivalmentaja://oauth2callback?code=abc123&state=s1", "s1")

    assertEquals(OuraOAuth.Redirect.Code("abc123"), redirect)
  }

  /**
   * The whole point of `state`. A redirect carrying someone else's value is not a failed login, it
   * is a request this device never made, and nothing about it may be exchanged.
   */
  @Test
  fun `a different state is refused`() {
    val redirect =
      OuraOAuth.readRedirect("treenivalmentaja://oauth2callback?code=abc123&state=other", "s1")

    assertEquals(OuraOAuth.Redirect.StateMismatch, redirect)
  }

  @Test
  fun `a redirect with no state at all is refused`() {
    val redirect = OuraOAuth.readRedirect("treenivalmentaja://oauth2callback?code=abc123", "s1")

    assertEquals(OuraOAuth.Redirect.StateMismatch, redirect)
  }

  /** Nothing is in flight, so nothing arriving can belong to it. */
  @Test
  fun `a redirect arriving when no login was started is refused`() {
    val redirect =
      OuraOAuth.readRedirect("treenivalmentaja://oauth2callback?code=abc123&state=s1", null)

    assertEquals(OuraOAuth.Redirect.StateMismatch, redirect)
  }

  /** The state is checked before the code is read, so a forged code never reaches an exchange. */
  @Test
  fun `a wrong state wins over a present code`() {
    val redirect =
      OuraOAuth.readRedirect(
        "treenivalmentaja://oauth2callback?code=stolen&state=wrong&error=none",
        "s1",
      )

    assertEquals(OuraOAuth.Redirect.StateMismatch, redirect)
  }

  @Test
  fun `a denial is reported as one`() {
    val redirect =
      OuraOAuth.readRedirect(
        "treenivalmentaja://oauth2callback?error=access_denied&state=s1",
        "s1",
      )

    assertEquals(OuraOAuth.Redirect.Denied("access_denied"), redirect)
  }

  @Test
  fun `another app's deep link is not ours`() {
    val redirect = OuraOAuth.readRedirect("https://example.com/?code=abc&state=s1", "s1")

    assertEquals(OuraOAuth.Redirect.Unusable, redirect)
  }

  /** A near-miss scheme is still not our redirect. */
  @Test
  fun `a lookalike scheme is not ours`() {
    val redirect =
      OuraOAuth.readRedirect("treenivalmentaja://oauth2callback-evil?code=abc&state=s1", "s1")

    assertEquals(OuraOAuth.Redirect.Unusable, redirect)
  }

  @Test
  fun `a redirect with neither code nor error is unusable`() {
    val redirect = OuraOAuth.readRedirect("treenivalmentaja://oauth2callback?state=s1", "s1")

    assertEquals(OuraOAuth.Redirect.Unusable, redirect)
  }

  @Test
  fun `no redirect at all is unusable`() {
    assertEquals(OuraOAuth.Redirect.Unusable, OuraOAuth.readRedirect(null, "s1"))
  }

  @Test
  fun `a percent-escaped code comes back decoded`() {
    val redirect =
      OuraOAuth.readRedirect("treenivalmentaja://oauth2callback?code=a%2Bb%3Dc&state=s1", "s1")

    assertEquals(OuraOAuth.Redirect.Code("a+b=c"), redirect)
  }
}
