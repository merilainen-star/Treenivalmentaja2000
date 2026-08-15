package fi.merilainen.treenivalmentaja.data.intervals

/**
 * The API-key store, in memory.
 *
 * Stands in for [IntervalsApiKeyStore] wherever the thing under test merely *uses* a store: the
 * real one encrypts with an Android Keystore key, which does not exist on the JVM.
 */
internal class FakeIntervalsApiKeyStorage(var key: String? = null) : IntervalsApiKeyStorage {

  var saves = 0
  var clears = 0

  override suspend fun apiKey(): String? = key

  override suspend fun saveApiKey(key: String) {
    saves++
    this.key = key
  }

  override suspend fun clearApiKey() {
    clears++
    key = null
  }
}
