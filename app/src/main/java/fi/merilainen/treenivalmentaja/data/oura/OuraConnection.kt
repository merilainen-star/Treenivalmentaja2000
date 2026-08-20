package fi.merilainen.treenivalmentaja.data.oura

import fi.merilainen.treenivalmentaja.data.local.dao.OuraDao
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings draws, and the only Oura state anything outside this package sees. */
sealed interface OuraConnectionState {

  /**
   * No client credentials yet — so Settings shows the two fields for them.
   *
   * This used to mean "this build was compiled without an `.env`, and there is nothing you can do
   * about it from here". It is now a step the user completes on the phone: Oura's personal access
   * tokens were withdrawn, so an application registered in Oura's developer portal is the only way
   * in, and its client id and secret are typed in rather than compiled in. See ADR-009.
   */
  data object NotConfigured : OuraConnectionState

  data object Disconnected : OuraConnectionState

  /** The browser is open, or the code is being exchanged. */
  data object Connecting : OuraConnectionState

  data object Connected : OuraConnectionState

  /** The last attempt failed, with a reason already written in Finnish. */
  data class Failed(val message: String) : OuraConnectionState
}

/**
 * Connecting and disconnecting Oura, and the state of it.
 *
 * Owns the awkward half of OAuth: the flow leaves the app entirely, the browser comes back through
 * an exported activity, and the process may have been killed in between. What makes that survivable
 * is that the verifier and the `state` live in [OuraTokenStore] rather than in memory, so returning
 * to a freshly started process still completes the login.
 *
 * @param onDisconnected clears the cached Oura rows. Passed in rather than reached for, because
 *   this class has no business knowing what a training plan is — and disconnecting must not touch
 *   one.
 */
class OuraConnection internal constructor(
  private val store: OuraTokenStorage,
  private val authService: OuraAuthService,
  private val credentials: OuraCredentialsSource,
  private val onDisconnected: suspend () -> Unit,
  private val random: SecureRandom = SecureRandom(),
) {

  /**
   * Starts pessimistic and is corrected by [refreshState], which the application calls at startup.
   *
   * It cannot start any other way: whether there are credentials now depends on what is on disk,
   * and reading that is a suspending, decrypting operation rather than something a constructor can
   * do.
   */
  private val _state = MutableStateFlow<OuraConnectionState>(OuraConnectionState.NotConfigured)
  val state: StateFlow<OuraConnectionState> = _state.asStateFlow()

  /** One authorization at a time; two browser tabs would race to write the pending verifier. */
  private val mutex = Mutex()

  /** Reads what is already on disk. Called at startup, before anything asks Oura for data. */
  suspend fun refreshState() {
    if (!credentials.credentials().isConfigured) {
      _state.value = OuraConnectionState.NotConfigured
      return
    }
    _state.value =
      if (store.hasTokens()) OuraConnectionState.Connected else OuraConnectionState.Disconnected
  }

  /**
   * Stores the client id and secret the user pasted from Oura's developer portal.
   *
   * This is what makes the whole feature reachable from a phone. The alternative the documents
   * assumed — compiling them into `BuildConfig` from a git-ignored `.env` — requires a PC, a
   * checkout and a local build, none of which this app's owner uses: test builds arrive as an APK
   * from a GitHub release. See ADR-009.
   *
   * @return false when either field is blank, which is the only validation possible here — whether
   *   they are the *right* credentials is a question only Oura can answer, and it does, at the
   *   token exchange.
   */
  suspend fun saveCredentials(clientId: String, clientSecret: String): CredentialSaveResult =
    mutex.withLock {
      val entered = OuraCredentials(clientId, clientSecret).trimmed()
      if (entered.clientId.isEmpty() || entered.clientSecret.isEmpty()) {
        return CredentialSaveResult.InvalidInput
      }
      val saved = store.saveCredentials(entered)
      if (saved != CredentialSaveResult.Success) {
        _state.value = OuraConnectionState.Failed(SECURE_SAVE_ERROR)
        return saved
      }
      // A half-finished login started under the previous credentials cannot be completed under
      // these ones.
      store.clearPending()
      refreshState()
      return CredentialSaveResult.Success
    }

  /** Forgets the client credentials as well as the tokens — the full way back to a clean app. */
  suspend fun forgetCredentials() {
    store.clear()
    store.clearCredentials()
    onDisconnected()
    refreshState()
  }

  /**
   * Starts a login and returns the URL to open in a browser.
   *
   * `null` when the build has no credentials — there is nothing to send, and opening a browser to
   * show Oura an empty `client_id` would produce a confusing error page instead of a clear state.
   *
   * The verifier is written down *before* the URL is handed out, so there is no window in which the
   * browser could come back to an app that has forgotten what it asked.
   */
  suspend fun beginAuthorization(): String? =
    mutex.withLock {
      val credentials = credentials.credentials()
      if (!credentials.isConfigured) {
        _state.value = OuraConnectionState.NotConfigured
        return null
      }
      val verifier = OuraOAuth.newCodeVerifier(random)
      val state = OuraOAuth.newState(random)
      if (store.savePending(verifier, state) != CredentialSaveResult.Success) {
        _state.value = OuraConnectionState.Failed(SECURE_SAVE_ERROR)
        return null
      }
      _state.value = OuraConnectionState.Connecting
      OuraOAuth.authorizationUrl(
        clientId = credentials.clientId,
        codeChallenge = OuraOAuth.codeChallengeOf(verifier),
        state = state,
      )
    }

  /**
   * Finishes a login from whatever the browser sent back.
   *
   * [redirectUri] arrives from an exported activity, so it is treated as hostile until the `state`
   * matches the one this device generated — see `docs/SECURITY.md`. A mismatch is reported as a
   * security failure and nothing is exchanged.
   */
  suspend fun completeAuthorization(redirectUri: String?) {
    mutex.withLock {
      // A build with no credentials cannot have started a login, so a redirect arriving at it came
      // from somewhere else entirely. Reporting it as a failed connection would be incoherent —
      // measured on a device: firing a forged redirect at an unconfigured build left the card
      // offering "Yritä uudelleen" for something that cannot be retried.
      if (!credentials.credentials().isConfigured) {
        _state.value = OuraConnectionState.NotConfigured
        return
      }
      val expectedState = store.pendingState()
      when (val redirect = OuraOAuth.readRedirect(redirectUri, expectedState)) {
        is OuraOAuth.Redirect.Code -> exchange(redirect.code)
        is OuraOAuth.Redirect.Denied -> {
          store.clearPending()
          _state.value =
            OuraConnectionState.Failed(
              if (redirect.error == "access_denied") "Oura-yhteyttä ei hyväksytty."
              else "Oura hylkäsi pyynnön: ${redirect.error}"
            )
        }
        OuraOAuth.Redirect.StateMismatch -> {
          store.clearPending()
          _state.value =
            OuraConnectionState.Failed(
              "Vastaus ei vastannut lähetettyä pyyntöä, joten sitä ei käytetty."
            )
        }
        OuraOAuth.Redirect.Unusable -> {
          // Not our redirect at all. Whatever is in flight is left alone rather than cancelled by
          // something that was never part of it.
        }
      }
    }
  }

  private suspend fun exchange(code: String) {
    val verifier = store.pendingVerifier()
    if (verifier == null) {
      store.clearPending()
      _state.value =
        OuraConnectionState.Failed("Kirjautuminen keskeytyi. Yritä yhdistämistä uudelleen.")
      return
    }
    _state.value = OuraConnectionState.Connecting
    try {
      val tokens = authService.exchange(code, verifier)
      if (store.save(tokens) == CredentialSaveResult.Success) {
        _state.value = OuraConnectionState.Connected
      } else {
        _state.value = OuraConnectionState.Failed(SECURE_SAVE_ERROR)
      }
    } catch (e: OuraException) {
      _state.value = OuraConnectionState.Failed(e.message ?: "Oura-yhteys epäonnistui.")
    } finally {
      // A verifier is good for one attempt whether it worked or not.
      store.clearPending()
    }
  }

  /** The user backed out of the browser without finishing. Not a failure worth a red message. */
  suspend fun cancelAuthorization() {
    mutex.withLock {
      if (_state.value is OuraConnectionState.Connecting) {
        store.clearPending()
        refreshState()
      }
    }
  }

  /**
   * Forgets the tokens and every Oura row cached from them. The training plan is untouched.
   *
   * **No call to a revoke endpoint**, which `docs/AUTHENTICATION.md` used to promise: the vendored
   * specification declares no `/oauth` paths at all, so there is nothing to call that could be
   * checked against it. Access is given up locally, and revoking the application itself is done
   * from Oura's own account settings. Documented rather than approximated with a guessed URL.
   */
  suspend fun disconnect() {
    store.clear()
    onDisconnected()
    refreshState()
  }

  /** Clears a message the user has read, without changing whether Oura is connected. */
  suspend fun dismissFailure() {
    if (_state.value is OuraConnectionState.Failed) refreshState()
  }

  /** The access token for [OuraClient], read fresh from the store on every call. */
  fun tokenSource(): OuraTokenSource = OuraTokenSource { store.load()?.accessToken }

  private companion object {
    const val SECURE_SAVE_ERROR =
      "Tunnuksia ei voitu tallentaa turvallisesti. Tarkista laitteen suojaus ja yritä uudelleen."
  }
}

/** Disconnecting drops the cached biometric rows and nothing else. */
internal suspend fun OuraDao.clearCachedOuraData() {
  clearDailySummaries()
  clearWorkouts()
}
