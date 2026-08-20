package fi.merilainen.treenivalmentaja.data.intervals

import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult

/**
 * The API-key store, in memory.
 *
 * Stands in for [IntervalsApiKeyStore] wherever the thing under test merely *uses* a store: the
 * real one encrypts with an Android Keystore key, which does not exist on the JVM.
 */
internal class FakeIntervalsApiKeyStorage(
  var key: String? = null,
  var saveResult: CredentialSaveResult = CredentialSaveResult.Success,
) : IntervalsApiKeyStorage {

  var saves = 0
  var clears = 0

  override suspend fun apiKey(): String? = key

  override suspend fun saveApiKey(key: String): CredentialSaveResult {
    saves++
    if (saveResult == CredentialSaveResult.Success) this.key = key
    return saveResult
  }

  override suspend fun clearApiKey() {
    clears++
    key = null
  }
}
