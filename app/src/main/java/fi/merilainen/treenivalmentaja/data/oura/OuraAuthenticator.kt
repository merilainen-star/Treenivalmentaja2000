package fi.merilainen.treenivalmentaja.data.oura

import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Renews the access token when Oura answers `401`, and retries the request once.
 *
 * This is the piece `docs/AUTHENTICATION.md` specifies and the reason the Oura client is on OkHttp
 * at all (ADR-007 in `docs/DECISIONS.md`). Two things it has to get right, and both are the kind
 * that only misbehave under load:
 *
 * **A refresh token is spent once.** Oura rotates them: a successful refresh invalidates the one it
 * was given. Two requests failing with `401` at the same moment would otherwise both try to spend
 * the same refresh token, and the second would be rejected — logging the user out precisely because
 * the app was busy. The whole body is therefore serialised, and a caller that arrives after someone
 * else has already renewed notices by comparing the token its request carried with the one now
 * stored, and simply retries with the new one instead of refreshing again.
 *
 * **A retry that fails is not retried.** Returning a request from here makes OkHttp reissue it, so
 * answering a second `401` on the same call with another request is an infinite loop. Counting the
 * chain of prior responses is what stops it.
 *
 * @param onRefreshFailed invoked when the refresh token itself is rejected. The tokens are already
 *   cleared by then; this is how the UI learns to say "connect again" rather than waiting for the
 *   next failed sync.
 */
internal class OuraAuthenticator(
  private val store: OuraTokenStorage,
  private val service: OuraAuthService,
  private val onRefreshFailed: () -> Unit = {},
) : Authenticator {

  private val lock = Any()

  override fun authenticate(route: Route?, response: Response): Request? {
    // OkHttp calls this on a background thread it owns, so blocking here is what it expects.
    if (responseCount(response) > 1) return null
    synchronized(lock) {
      val stored = runBlocking { store.load() } ?: return null
      val attempted = response.request.header("Authorization")
      if (attempted != bearer(stored.accessToken)) {
        // Someone else refreshed while this request was in flight. Nothing to renew — just go
        // again with what is now stored.
        return response.request.retryWith(stored.accessToken)
      }
      val refreshToken = stored.refreshToken ?: return null
      val renewed =
        try {
          runBlocking { service.refresh(refreshToken) }
        } catch (e: OuraException) {
          // A rejected refresh token cannot be recovered from, and keeping it would make every
          // later request repeat this. Dropping it is what turns a dead connection into a visible
          // "connect again" instead of a silent, permanent failure.
          if (e is OuraAuthorizationException) {
            runBlocking { store.clear() }
            onRefreshFailed()
          }
          return null
        }
      val saved = runBlocking {
        // Oura returns a new refresh token; if it ever does not, the old one is still the only one
        // there is, and dropping it would end the connection at the next expiry.
        store.save(renewed.copy(refreshToken = renewed.refreshToken ?: refreshToken))
      }
      if (saved != CredentialSaveResult.Success) {
        // Keeping the expired token would make refreshState report Connected after the secure
        // write failed. Drop it so the UI can never claim this connection is usable.
        runBlocking { store.clear() }
        onRefreshFailed()
        return null
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
