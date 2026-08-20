package fi.merilainen.treenivalmentaja.data.oura

import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult

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
  var saveResult: CredentialSaveResult = CredentialSaveResult.Success

  override suspend fun load(): OuraTokens? = tokens

  override suspend fun save(tokens: OuraTokens): CredentialSaveResult {
    saves++
    if (saveResult == CredentialSaveResult.Success) this.tokens = tokens
    return saveResult
  }

  override suspend fun clear() {
    clears++
    tokens = null
    verifier = null
    state = null
  }

  override suspend fun savePending(codeVerifier: String, state: String): CredentialSaveResult {
    if (saveResult == CredentialSaveResult.Success) {
      this.verifier = codeVerifier
      this.state = state
    }
    return saveResult
  }

  override suspend fun pendingVerifier(): String? = verifier

  override suspend fun pendingState(): String? = state

  override suspend fun clearPending() {
    verifier = null
    state = null
  }

  override suspend fun credentials(): OuraCredentials? = storedCredentials

  override suspend fun saveCredentials(credentials: OuraCredentials): CredentialSaveResult {
    if (saveResult == CredentialSaveResult.Success) storedCredentials = credentials
    return saveResult
  }

  override suspend fun clearCredentials() {
    storedCredentials = null
  }
}
