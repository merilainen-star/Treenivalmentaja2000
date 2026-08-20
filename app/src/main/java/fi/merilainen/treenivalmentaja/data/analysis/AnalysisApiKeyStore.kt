package fi.merilainen.treenivalmentaja.data.analysis

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import fi.merilainen.treenivalmentaja.data.security.EncryptionResult
import fi.merilainen.treenivalmentaja.data.security.KeystoreCipher
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where one provider's API key lives.
 *
 * An interface for the reason the Oura and intervals.icu key stores are: the implementation below
 * needs an Android Keystore, which has no JVM equivalent, so anything that merely *uses* a key store
 * would otherwise need an emulator to test.
 */
internal interface AnalysisApiKeyStorage {

  suspend fun apiKey(): String?

  suspend fun saveApiKey(key: String): CredentialSaveResult

  suspend fun clearApiKey()

  suspend fun hasApiKey(): Boolean = apiKey() != null
}

/**
 * One provider's API key on disk, encrypted with a key that cannot leave the device.
 *
 * The same construction as `IntervalsApiKeyStore` and `OuraTokenStore`, deliberately unchanged — a
 * 256-bit AES key generated inside the Android Keystore where it is not extractable, AES-GCM so a
 * tampered ciphertext fails to decrypt rather than decrypting to something else, and the IV stored
 * beside the ciphertext because reusing a GCM nonce is the one mistake that breaks it completely.
 * See `OuraTokenStore` for the full rationale and
 * [ADR-008](../../../../../../../../docs/DECISIONS.md) for why not `EncryptedSharedPreferences`.
 *
 * **One instance per provider, each with its own preferences file and its own Keystore alias**, both
 * derived from [provider]. Three providers means three secrets, and clearing one must not be able to
 * touch another — that has to be a property of the layout rather than of careful key naming. It also
 * means a key pasted into the wrong field cannot silently authenticate somewhere else: it is stored
 * under the provider whose field it was typed into, and only that provider's client reads it.
 */
internal class AnalysisApiKeyStore(
  context: Context,
  private val provider: AnalysisProvider,
) : AnalysisApiKeyStorage {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(fileName(), Context.MODE_PRIVATE)
  private val cipher = KeystoreCipher(alias())

  private fun fileName(): String = "analysis_${provider.name.lowercase()}_credentials"

  private fun alias(): String = "treenivalmentaja.analysis.${provider.name.lowercase()}.apikey"

  override suspend fun apiKey(): String? =
    withContext(Dispatchers.IO) { cipher.decrypt(prefs.getString(KEY_API_KEY, null)) }

  override suspend fun saveApiKey(key: String): CredentialSaveResult =
    withContext(Dispatchers.IO) {
      val encrypted = (cipher.encrypt(key) as? EncryptionResult.Success)?.encoded
        ?: return@withContext CredentialSaveResult.StorageFailure
      if (prefs.edit().putString(KEY_API_KEY, encrypted).commit()) CredentialSaveResult.Success
      else CredentialSaveResult.StorageFailure
    }

  override suspend fun clearApiKey() {
    withContext(Dispatchers.IO) { prefs.edit(commit = true) { remove(KEY_API_KEY) } }
  }

  private companion object {
    const val KEY_API_KEY = "api_key"
  }
}
