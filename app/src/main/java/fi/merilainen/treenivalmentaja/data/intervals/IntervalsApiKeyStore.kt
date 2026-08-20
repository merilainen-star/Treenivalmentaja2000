package fi.merilainen.treenivalmentaja.data.intervals

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import fi.merilainen.treenivalmentaja.data.security.EncryptionResult
import fi.merilainen.treenivalmentaja.data.security.KeystoreCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the intervals.icu API key lives.
 *
 * An interface for the reason the Oura token store is one: the implementation below needs an
 * Android Keystore, which has no JVM equivalent, so anything that merely *uses* a key store would
 * otherwise need an emulator to test.
 *
 * Much smaller than the OAuth stores this replaces. There is one secret, no refresh token, no
 * expiry and no half-finished authorization to survive a process death — which is the whole
 * argument for a personal API key in a single-user app.
 */
internal interface IntervalsApiKeyStorage {

  suspend fun apiKey(): String?

  suspend fun saveApiKey(key: String): CredentialSaveResult

  suspend fun clearApiKey()

  suspend fun hasApiKey(): Boolean = apiKey() != null
}

/**
 * The API key on disk, encrypted with a key that cannot leave the device.
 *
 * The same construction as the Oura token store and for the same reasons — a 256-bit AES key
 * generated inside the Android Keystore where it is not extractable, AES-GCM so a tampered
 * ciphertext fails to decrypt rather than decrypting to something else, and the IV stored beside
 * the ciphertext because reusing a GCM nonce is the one mistake that breaks it completely. See
 * `OuraTokenStore` for the full rationale and
 * [ADR-008](../../../../../../../../docs/DECISIONS.md) for why not `EncryptedSharedPreferences`.
 *
 * Its own preferences file and its own Keystore alias: clearing one service's secret must not be
 * able to touch another's, and separate files make that a property of the layout rather than of
 * careful key naming.
 *
 * Excluded from backups — see `res/xml/backup_rules.xml`. Restoring the ciphertext onto a device
 * whose Keystore has none of the key would leave the app holding bytes it can never read.
 */
internal class IntervalsApiKeyStore(context: Context) : IntervalsApiKeyStorage {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val cipher = KeystoreCipher(ALIAS)

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
    const val FILE = "intervals_credentials"

    const val ALIAS = "treenivalmentaja.intervals.apikey"

    const val KEY_API_KEY = "api_key"
  }
}
