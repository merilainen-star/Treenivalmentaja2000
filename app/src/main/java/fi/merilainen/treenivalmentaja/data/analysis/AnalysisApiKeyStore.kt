package fi.merilainen.treenivalmentaja.data.analysis

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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

  suspend fun saveApiKey(key: String)

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

  private fun fileName(): String = "analysis_${provider.name.lowercase()}_credentials"

  private fun alias(): String = "treenivalmentaja.analysis.${provider.name.lowercase()}.apikey"

  override suspend fun apiKey(): String? =
    withContext(Dispatchers.IO) { decrypt(prefs.getString(KEY_API_KEY, null)) }

  override suspend fun saveApiKey(key: String) {
    withContext(Dispatchers.IO) {
      val encrypted = encrypt(key) ?: return@withContext
      prefs.edit(commit = true) { putString(KEY_API_KEY, encrypted) }
    }
  }

  override suspend fun clearApiKey() {
    withContext(Dispatchers.IO) { prefs.edit(commit = true) { remove(KEY_API_KEY) } }
  }

  // ------------------------------------------------------------------ crypto

  private fun encrypt(plain: String): String? =
    try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, secretKey())
      val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      val iv = cipher.iv
      // [iv length][iv][ciphertext] — the length is written down rather than assumed, so a future
      // provider choosing a different nonce size does not silently corrupt.
      val packed = ByteArray(1 + iv.size + encrypted.size)
      packed[0] = iv.size.toByte()
      iv.copyInto(packed, 1)
      encrypted.copyInto(packed, 1 + iv.size)
      Base64.getEncoder().encodeToString(packed)
    } catch (e: GeneralSecurityException) {
      null
    }

  /**
   * `null` for anything that cannot be read back, and that is an outcome rather than a swallowed
   * error: a Keystore key is gone after a factory reset or a restored backup, and the honest reading
   * of "the key cannot be decrypted" is "no key", which asks the user to paste one again.
   */
  private fun decrypt(stored: String?): String? {
    if (stored.isNullOrEmpty()) return null
    return try {
      val packed = Base64.getDecoder().decode(stored)
      val ivSize = packed[0].toInt()
      if (ivSize <= 0 || packed.size <= 1 + ivSize) return null
      val iv = packed.copyOfRange(1, 1 + ivSize)
      val encrypted = packed.copyOfRange(1 + ivSize, packed.size)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
      String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
      null
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(PROVIDER_ANDROID).apply { load(null) }
    (keyStore.getEntry(alias(), null) as? KeyStore.SecretKeyEntry)?.let {
      return it.secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER_ANDROID)
    generator.init(
      KeyGenParameterSpec.Builder(
          alias(),
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        // Deliberately not requiring user authentication: this is only ever read from a foreground
        // tap, so a prompt would be a second unlock on a phone that was just unlocked.
        .setUserAuthenticationRequired(false)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val PROVIDER_ANDROID = "AndroidKeyStore"

    const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val TAG_BITS = 128

    const val KEY_API_KEY = "api_key"
  }
}
