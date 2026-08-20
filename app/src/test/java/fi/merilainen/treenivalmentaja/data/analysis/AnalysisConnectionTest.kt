package fi.merilainen.treenivalmentaja.data.analysis

import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisConnectionTest {

  private class FakeStore(
    var result: CredentialSaveResult = CredentialSaveResult.Success,
  ) : AnalysisApiKeyStorage {
    var key: String? = null
    override suspend fun apiKey(): String? = key
    override suspend fun saveApiKey(key: String): CredentialSaveResult {
      if (result == CredentialSaveResult.Success) this.key = key
      return result
    }
    override suspend fun clearApiKey() {
      key = null
    }
  }

  @Test
  fun `storage failure is surfaced and provider stays unconfigured`() = runTest {
    val store = FakeStore(CredentialSaveResult.StorageFailure)
    val connection = AnalysisConnection(mapOf(AnalysisProvider.OPENAI to store))

    val result = connection.saveApiKey(AnalysisProvider.OPENAI, "secret")

    assertEquals(CredentialSaveResult.StorageFailure, result)
    assertNull(store.key)
    assertEquals(emptySet<AnalysisProvider>(), connection.configured.value)
    assertEquals(AnalysisProvider.OPENAI, connection.saveFailure.value)
  }
}
