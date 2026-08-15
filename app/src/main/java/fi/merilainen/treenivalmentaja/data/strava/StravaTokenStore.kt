package fi.merilainen.treenivalmentaja.data.strava

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
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
 * Where Strava's tokens live. Same split as
 * [fi.merilainen.treenivalmentaja.data.oura.OuraTokenStorage]: an interface because the Keystore
 * has no JVM equivalent, so unit tests of anything that merely *uses* the store run against an
 * in-memory fake and the real implementation is covered where a Keystore exists.
 *
 * No pending verifier, because Strava's flow has no PKCE — only the `state` is held between
 * opening the browser and coming back.
 */
internal interface StravaTokenStorage {

  suspend fun load(): StravaTokens?

  suspend fun save(tokens: StravaTokens)

  suspend fun clear()

  suspend fun hasTokens(): Boolean = load() != null

  suspend fun savePendingState(state: String)

  suspend fun pendingState(): String?

  suspend fun clearPending()

  /** Outlive a disconnect on purpose: reconnecting must not mean pasting them again. */
  suspend fun credentials(): StravaCredentials?

  suspend fun saveCredentials(credentials: StravaCredentials)

  suspend fun clearCredentials()
}

/**
 * Strava's tokens on disk, encrypted the way Oura's are and for the same reasons — a 256-bit AES
 * key that cannot leave the Android Keystore, AES-GCM so tampering fails loudly, IV stored beside
 * the ciphertext. See `OuraTokenStore` for the full rationale and ADR-008 for why not
 * `EncryptedSharedPreferences`.
 *
 * Its own preferences file and its own Keystore alias rather than shared ones: disconnecting one
 * service must not be able to touch the other's tokens, and separate files make that a property of
 * the layout rather than of careful key naming.
 *
 * The file is excluded from backups alongside Oura's — see `res/xml/backup_rules.xml`.
 */
internal class StravaTokenStore(context: Context) : StravaTokenStorage {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  override suspend fun load(): StravaTokens? =
    withContext(Dispatchers.IO) {
      val access = decrypt(prefs.getString(KEY_ACCESS, null)) ?: return@withContext null
      StravaTokens(
        accessToken = access,
        refreshToken = decrypt(prefs.getString(KEY_REFRESH, null)),
        expiresAtUtc = prefs.getLong(KEY_EXPIRES_AT, StravaTokens.UNKNOWN_EXPIRY),
      )
    }

  override suspend fun save(tokens: StravaTokens) {
    withContext(Dispatchers.IO) {
      val access = encrypt(tokens.accessToken) ?: return@withContext
      prefs.edit(commit = true) {
        putString(KEY_ACCESS, access)
        val refresh = tokens.refreshToken?.let { encrypt(it) }
        if (refresh != null) putString(KEY_REFRESH, refresh) else remove(KEY_REFRESH)
        putLong(KEY_EXPIRES_AT, tokens.expiresAtUtc)
      }
    }
  }

  /** The tokens and any half-finished authorization — **not** the client credentials. */
  override suspend fun clear() {
    withContext(Dispatchers.IO) {
      prefs.edit(commit = true) {
        remove(KEY_ACCESS)
        remove(KEY_REFRESH)
        remove(KEY_EXPIRES_AT)
        remove(KEY_STATE)
      }
    }
  }

  /**
   * The `state`, held between opening the browser and coming back — on disk because the round trip
   * leaves the app in the background where the process may be killed. Not secret the way a PKCE
   * verifier is, but stored in the same file so clearing one flow clears all of it.
   */
  override suspend fun savePendingState(state: String) {
    withContext(Dispatchers.IO) { prefs.edit(commit = true) { putString(KEY_STATE, state) } }
  }

  override suspend fun pendingState(): String? =
    withContext(Dispatchers.IO) { prefs.getString(KEY_STATE, null) }

  override suspend fun clearPending() {
    withContext(Dispatchers.IO) { prefs.edit(commit = true) { remove(KEY_STATE) } }
  }

  override suspend fun credentials(): StravaCredentials? =
    withContext(Dispatchers.IO) {
      val id = decrypt(prefs.getString(KEY_CLIENT_ID, null)) ?: return@withContext null
      val secret = decrypt(prefs.getString(KEY_CLIENT_SECRET, null)) ?: return@withContext null
      StravaCredentials(clientId = id, clientSecret = secret)
    }

  override suspend fun saveCredentials(credentials: StravaCredentials) {
    withContext(Dispatchers.IO) {
      val id = encrypt(credentials.clientId) ?: return@withContext
      val secret = encrypt(credentials.clientSecret) ?: return@withContext
      prefs.edit(commit = true) {
        putString(KEY_CLIENT_ID, id)
        putString(KEY_CLIENT_SECRET, secret)
      }
    }
  }

  override suspend fun clearCredentials() {
    withContext(Dispatchers.IO) {
      prefs.edit(commit = true) {
        remove(KEY_CLIENT_ID)
        remove(KEY_CLIENT_SECRET)
      }
    }
  }

  // ------------------------------------------------------------------ crypto

  private fun encrypt(plain: String): String? =
    try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, secretKey())
      val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      val iv = cipher.iv
      val packed = ByteArray(1 + iv.size + encrypted.size)
      packed[0] = iv.size.toByte()
      iv.copyInto(packed, 1)
      encrypted.copyInto(packed, 1 + iv.size)
      Base64.getEncoder().encodeToString(packed)
    } catch (e: GeneralSecurityException) {
      null
    }

  /** `null` for anything that cannot be read back — "Strava is not connected", not a crash. */
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
    val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
      return it.secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(
          ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setUserAuthenticationRequired(false)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val FILE = "strava_tokens"

    const val PROVIDER = "AndroidKeyStore"

    const val ALIAS = "treenivalmentaja.strava.tokens"

    const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val TAG_BITS = 128

    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_STATE = "pending_state"
    const val KEY_CLIENT_ID = "client_id"
    const val KEY_CLIENT_SECRET = "client_secret"
  }
}
