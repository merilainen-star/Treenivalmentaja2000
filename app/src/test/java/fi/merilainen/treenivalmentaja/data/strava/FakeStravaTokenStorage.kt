package fi.merilainen.treenivalmentaja.data.strava

/**
 * The Strava token store, in memory.
 *
 * Stands in for [StravaTokenStore] wherever the thing under test merely *uses* a store: the real
 * one encrypts with an Android Keystore key, which does not exist on the JVM.
 */
internal class FakeStravaTokenStorage(
  var tokens: StravaTokens? = null,
  var state: String? = null,
  var storedCredentials: StravaCredentials? = null,
) : StravaTokenStorage {

  var saves = 0
  var clears = 0

  override suspend fun load(): StravaTokens? = tokens

  override suspend fun save(tokens: StravaTokens) {
    saves++
    this.tokens = tokens
  }

  override suspend fun clear() {
    clears++
    tokens = null
    state = null
  }

  override suspend fun savePendingState(state: String) {
    this.state = state
  }

  override suspend fun pendingState(): String? = state

  override suspend fun clearPending() {
    state = null
  }

  override suspend fun credentials(): StravaCredentials? = storedCredentials

  override suspend fun saveCredentials(credentials: StravaCredentials) {
    storedCredentials = credentials
  }

  override suspend fun clearCredentials() {
    storedCredentials = null
  }
}
