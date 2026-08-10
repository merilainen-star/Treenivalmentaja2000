package fi.merilainen.treenivalmentaja.data.oura

/**
 * The token store, in memory.
 *
 * Stands in for [OuraTokenStore] wherever the thing under test merely *uses* a store: the real one
 * encrypts with an Android Keystore key, which does not exist on the JVM, so anything touching it
 * would need an emulator. The real implementation has its own instrumented test, and this one
 * exists so the state machine above it does not have to.
 */
internal class FakeOuraTokenStorage(
  var tokens: OuraTokens? = null,
  var verifier: String? = null,
  var state: String? = null,
  var storedCredentials: OuraCredentials? = null,
) : OuraTokenStorage {

  var saves = 0
  var clears = 0

  override suspend fun load(): OuraTokens? = tokens

  override suspend fun save(tokens: OuraTokens) {
    saves++
    this.tokens = tokens
  }

  override suspend fun clear() {
    clears++
    tokens = null
    verifier = null
    state = null
  }

  override suspend fun savePending(codeVerifier: String, state: String) {
    this.verifier = codeVerifier
    this.state = state
  }

  override suspend fun pendingVerifier(): String? = verifier

  override suspend fun pendingState(): String? = state

  override suspend fun clearPending() {
    verifier = null
    state = null
  }

  override suspend fun credentials(): OuraCredentials? = storedCredentials

  override suspend fun saveCredentials(credentials: OuraCredentials) {
    storedCredentials = credentials
  }

  override suspend fun clearCredentials() {
    storedCredentials = null
  }
}
