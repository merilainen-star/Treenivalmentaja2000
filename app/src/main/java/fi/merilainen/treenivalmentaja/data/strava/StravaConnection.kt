package fi.merilainen.treenivalmentaja.data.strava

import fi.merilainen.treenivalmentaja.data.local.dao.StravaDao
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings draws, and the only Strava state anything outside this package sees. */
sealed interface StravaConnectionState {

  /** No client credentials yet — Settings shows the two fields for them. */
  data object NotConfigured : StravaConnectionState

  data object Disconnected : StravaConnectionState

  /** The browser is open, or the code is being exchanged. */
  data object Connecting : StravaConnectionState

  data object Connected : StravaConnectionState

  /** The last attempt failed, with a reason already written in Finnish. */
  data class Failed(val message: String) : StravaConnectionState
}

/**
 * Connecting and disconnecting Strava, and the state of it.
 *
 * The same shape as [fi.merilainen.treenivalmentaja.data.oura.OuraConnection] minus PKCE: the flow
 * leaves the app, the browser comes back through an exported activity, and the `state` held in
 * [StravaTokenStore] is what survives the process being killed in between.
 *
 * @param onDisconnected clears the cached Strava rows. Passed in rather than reached for — this
 *   class has no business knowing what a training plan is.
 */
class StravaConnection internal constructor(
  private val store: StravaTokenStorage,
  private val authService: StravaAuthService,
  private val onDisconnected: suspend () -> Unit,
  private val random: SecureRandom = SecureRandom(),
) {

  private val _state = MutableStateFlow<StravaConnectionState>(StravaConnectionState.NotConfigured)
  val state: StateFlow<StravaConnectionState> = _state.asStateFlow()

  /** One authorization at a time; two browser tabs would race to write the pending state. */
  private val mutex = Mutex()

  /** Reads what is already on disk. Called at startup, before anything asks Strava for data. */
  suspend fun refreshState() {
    if (store.credentials()?.isConfigured != true) {
      _state.value = StravaConnectionState.NotConfigured
      return
    }
    _state.value =
      if (store.hasTokens()) StravaConnectionState.Connected
      else StravaConnectionState.Disconnected
  }

  /**
   * Stores the client id and secret the user pasted from Strava's API settings page.
   *
   * @return false when either field is blank — whether they are the *right* credentials is a
   *   question only Strava can answer, at the token exchange.
   */
  suspend fun saveCredentials(clientId: String, clientSecret: String): Boolean =
    mutex.withLock {
      val entered = StravaCredentials(clientId, clientSecret).trimmed()
      if (!entered.isConfigured) return false
      store.saveCredentials(entered)
      // A half-finished login started under the previous credentials cannot be completed under
      // these ones.
      store.clearPending()
      refreshState()
      return true
    }

  /** Forgets the client credentials as well as the tokens — the full way back to a clean app. */
  suspend fun forgetCredentials() {
    store.clear()
    store.clearCredentials()
    onDisconnected()
    refreshState()
  }

  /**
   * Starts a login and returns the URL to open in a browser, or `null` without credentials.
   *
   * The `state` is written down *before* the URL is handed out, so there is no window in which the
   * browser could come back to an app that has forgotten what it asked.
   */
  suspend fun beginAuthorization(): String? =
    mutex.withLock {
      val credentials = store.credentials()
      if (credentials?.isConfigured != true) {
        _state.value = StravaConnectionState.NotConfigured
        return null
      }
      val state = StravaOAuth.newState(random)
      store.savePendingState(state)
      _state.value = StravaConnectionState.Connecting
      StravaOAuth.authorizationUrl(clientId = credentials.clientId, state = state)
    }

  /**
   * Finishes a login from whatever the browser sent back.
   *
   * [redirectUri] arrives from an exported activity, so it is treated as hostile until the `state`
   * matches the one this device generated — see `docs/SECURITY.md`.
   */
  suspend fun completeAuthorization(redirectUri: String?) {
    mutex.withLock {
      if (store.credentials()?.isConfigured != true) {
        _state.value = StravaConnectionState.NotConfigured
        return
      }
      val expectedState = store.pendingState()
      when (val redirect = StravaOAuth.readRedirect(redirectUri, expectedState)) {
        is StravaOAuth.Redirect.Code -> exchange(redirect)
        is StravaOAuth.Redirect.Denied -> {
          store.clearPending()
          _state.value =
            StravaConnectionState.Failed(
              if (redirect.error == "access_denied") "Strava-yhteyttä ei hyväksytty."
              else "Strava hylkäsi pyynnön: ${redirect.error}"
            )
        }
        StravaOAuth.Redirect.StateMismatch -> {
          store.clearPending()
          _state.value =
            StravaConnectionState.Failed(
              "Vastaus ei vastannut lähetettyä pyyntöä, joten sitä ei käytetty."
            )
        }
        StravaOAuth.Redirect.Unusable -> {
          // Not our redirect at all. Whatever is in flight is left alone.
        }
      }
    }
  }

  private suspend fun exchange(redirect: StravaOAuth.Redirect.Code) {
    // Strava lets the user untick scopes on its consent screen, and answers with what was actually
    // granted. A connection without activity:read_all can authenticate but never return a run,
    // which from the app's side looks identical to an athlete who never trains — so it is refused
    // here, where the reason can still be said out loud.
    val granted = redirect.scope
    if (granted != null && !granted.contains("activity:read")) {
      store.clearPending()
      _state.value =
        StravaConnectionState.Failed(
          "Strava-yhteys ei saanut lupaa lukea harjoituksia. Yhdistä uudelleen ja salli " +
            "harjoitusten luku."
        )
      return
    }
    _state.value = StravaConnectionState.Connecting
    try {
      val tokens = authService.exchange(redirect.code)
      store.save(tokens)
      _state.value = StravaConnectionState.Connected
    } catch (e: StravaException) {
      _state.value = StravaConnectionState.Failed(e.message ?: "Strava-yhteys epäonnistui.")
    } finally {
      store.clearPending()
    }
  }

  /** The user backed out of the browser without finishing. Not a failure worth a red message. */
  suspend fun cancelAuthorization() {
    mutex.withLock {
      if (_state.value is StravaConnectionState.Connecting) {
        store.clearPending()
        refreshState()
      }
    }
  }

  /**
   * Forgets the tokens and every Strava row cached from them. The training plan is untouched.
   *
   * Strava does have a deauthorize endpoint (`POST /oauth/deauthorize`), but it is deliberately
   * not called: it would revoke the whole application for the athlete, and a failed network call
   * would leave the app unsure whether it is still authorized. Access is given up locally, and the
   * application can also be revoked from Strava's own settings.
   */
  suspend fun disconnect() {
    store.clear()
    onDisconnected()
    refreshState()
  }

  /** Clears a message the user has read, without changing whether Strava is connected. */
  suspend fun dismissFailure() {
    if (_state.value is StravaConnectionState.Failed) refreshState()
  }

  /** The access token for [StravaClient], read fresh from the store on every call. */
  fun tokenSource(): StravaTokenSource = StravaTokenSource { store.load()?.accessToken }
}

/** Disconnecting drops the cached activity rows and nothing else. */
internal suspend fun StravaDao.clearCachedStravaData() {
  clearActivities()
}
