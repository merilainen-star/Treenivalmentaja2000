package fi.merilainen.treenivalmentaja.data.strava

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authorization URL and what a redirect is allowed to mean.
 *
 * The second half is the security-relevant one, for the same reason it is on the Oura side: the
 * activity that receives a redirect is exported, so anything on the device can start it with any
 * URI at all, and [StravaOAuth.readRedirect] is what stands between that and a token exchange.
 *
 * There is no PKCE here to test — Strava's token endpoint authenticates with the client secret and
 * accepts no `code_verifier` — which puts the whole burden on `state`.
 */
class StravaOAuthTest {

  @Test
  fun `two states are not the same`() {
    assertNotEquals(StravaOAuth.newState(), StravaOAuth.newState())
  }

  @Test
  fun `a state carries no padding or url-unsafe characters`() {
    val state = StravaOAuth.newState()

    assertFalse(state, state.contains("="))
    assertFalse(state, state.contains("+"))
    assertFalse(state, state.contains("/"))
  }

  // ------------------------------------------------------------------ the authorization URL

  @Test
  fun `the authorization url carries everything Strava needs`() {
    val url = StravaOAuth.authorizationUrl("client-123", "state-xyz")

    assertTrue(url, url.startsWith("https://www.strava.com/oauth/mobile/authorize?"))
    assertTrue(url, url.contains("response_type=code"))
    assertTrue(url, url.contains("client_id=client-123"))
    assertTrue(url, url.contains("state=state-xyz"))
  }

  /**
   * `activity:read_all` and nothing else. `read_all` rather than `read` because private runs are
   * the common case and a scope that returns only public ones would look like an empty history;
   * write and profile scopes are absent because nothing here posts to Strava.
   */
  @Test
  fun `only the activity read scope is requested`() {
    val url = StravaOAuth.authorizationUrl("c", "s")

    assertTrue(url, url.contains("scope=activity%3Aread_all"))
    assertFalse(url, url.contains("write"))
    assertFalse(url, url.contains("profile"))
  }

  @Test
  fun `the redirect uri is escaped, not pasted in raw`() {
    val url = StravaOAuth.authorizationUrl("c", "s")

    assertTrue(url, url.contains("redirect_uri=treenivalmentaja%3A%2F%2Flocalhost%2Fstrava"))
  }

  // ------------------------------------------------------------------ reading a redirect

  @Test
  fun `a matching state yields the code and the granted scope`() {
    val redirect =
      StravaOAuth.readRedirect(
        "treenivalmentaja://localhost/strava?code=abc123&scope=activity%3Aread_all&state=s1",
        "s1",
      )

    assertEquals(StravaOAuth.Redirect.Code("abc123", "activity:read_all"), redirect)
  }

  @Test
  fun `a code with no scope parameter is still usable`() {
    val redirect =
      StravaOAuth.readRedirect("treenivalmentaja://localhost/strava?code=abc123&state=s1", "s1")

    assertEquals(StravaOAuth.Redirect.Code("abc123", null), redirect)
  }

  /**
   * The whole point of `state`: a redirect carrying someone else's value is a request this device
   * never made, and nothing about it may be exchanged.
   */
  @Test
  fun `a different state is refused`() {
    val redirect =
      StravaOAuth.readRedirect("treenivalmentaja://localhost/strava?code=abc123&state=other", "s1")

    assertEquals(StravaOAuth.Redirect.StateMismatch, redirect)
  }

  @Test
  fun `a redirect with no state at all is refused`() {
    val redirect = StravaOAuth.readRedirect("treenivalmentaja://localhost/strava?code=abc", "s1")

    assertEquals(StravaOAuth.Redirect.StateMismatch, redirect)
  }

  /** Nothing is in flight, so nothing arriving can belong to it. */
  @Test
  fun `a redirect arriving when no login was started is refused`() {
    val redirect =
      StravaOAuth.readRedirect("treenivalmentaja://localhost/strava?code=abc&state=s1", null)

    assertEquals(StravaOAuth.Redirect.StateMismatch, redirect)
  }

  /** The state is checked before the code is read, so a forged code never reaches an exchange. */
  @Test
  fun `a wrong state wins over a present code`() {
    val redirect =
      StravaOAuth.readRedirect(
        "treenivalmentaja://localhost/strava?code=stolen&state=wrong&error=none",
        "s1",
      )

    assertEquals(StravaOAuth.Redirect.StateMismatch, redirect)
  }

  @Test
  fun `a denial is reported as one`() {
    val redirect =
      StravaOAuth.readRedirect(
        "treenivalmentaja://localhost/strava?error=access_denied&state=s1",
        "s1",
      )

    assertEquals(StravaOAuth.Redirect.Denied("access_denied"), redirect)
  }

  @Test
  fun `another app's deep link is not ours`() {
    val redirect = StravaOAuth.readRedirect("https://example.com/?code=abc&state=s1", "s1")

    assertEquals(StravaOAuth.Redirect.Unusable, redirect)
  }

  /** A near-miss path is still not our redirect — and neither is Oura's own. */
  @Test
  fun `a lookalike path is not ours`() {
    assertEquals(
      StravaOAuth.Redirect.Unusable,
      StravaOAuth.readRedirect("treenivalmentaja://localhost/strava-evil?code=a&state=s1", "s1"),
    )
    assertEquals(
      StravaOAuth.Redirect.Unusable,
      StravaOAuth.readRedirect("treenivalmentaja://oauth2callback?code=a&state=s1", "s1"),
    )
  }

  @Test
  fun `a redirect with neither code nor error is unusable`() {
    val redirect = StravaOAuth.readRedirect("treenivalmentaja://localhost/strava?state=s1", "s1")

    assertEquals(StravaOAuth.Redirect.Unusable, redirect)
  }

  @Test
  fun `no redirect at all is unusable`() {
    assertEquals(StravaOAuth.Redirect.Unusable, StravaOAuth.readRedirect(null, "s1"))
  }
}
