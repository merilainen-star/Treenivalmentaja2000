package fi.merilainen.treenivalmentaja.data.strava

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews the Strava access token when the API answers `401`, and retries the request once.
 *
 * The same two rules as [fi.merilainen.treenivalmentaja.data.oura.OuraAuthenticator], for the same
 * reasons: a refresh token is spent under a lock so two concurrent `401`s cannot both spend it,
 * and a retry that fails again is not retried. Strava usually returns the same refresh token
 * rather than rotating, but the code stores whatever came back and assumes nothing.
 *
 * @param onRefreshFailed invoked when the refresh token itself is rejected; the tokens are already
 *   cleared by then, and this is how the UI learns to say "connect again".
 */
internal class StravaAuthenticator(
  private val store: StravaTokenStorage,
  private val service: StravaAuthService,
  private val onRefreshFailed: () -> Unit = {},
) : Authenticator {

  private val lock = Any()

  override fun authenticate(route: Route?, response: Response): Request? {
    if (responseCount(response) > 1) return null
    synchronized(lock) {
      val stored = runBlocking { store.load() } ?: return null
      val attempted = response.request.header("Authorization")
      if (attempted != bearer(stored.accessToken)) {
        // Someone else refreshed while this request was in flight — go again with what is stored.
        return response.request.retryWith(stored.accessToken)
      }
      val refreshToken = stored.refreshToken ?: return null
      val renewed =
        try {
          runBlocking { service.refresh(refreshToken) }
        } catch (e: StravaException) {
          if (e is StravaAuthorizationException) {
            runBlocking { store.clear() }
            onRefreshFailed()
          }
          return null
        }
      runBlocking {
        store.save(renewed.copy(refreshToken = renewed.refreshToken ?: refreshToken))
      }
      return response.request.retryWith(renewed.accessToken)
    }
  }

  private fun Request.retryWith(accessToken: String): Request =
    newBuilder().header("Authorization", bearer(accessToken)).build()

  private fun bearer(accessToken: String) = "Bearer $accessToken"

  private fun responseCount(response: Response): Int {
    var count = 1
    var prior = response.priorResponse
    while (prior != null) {
      count++
      prior = prior.priorResponse
    }
    return count
  }
}
