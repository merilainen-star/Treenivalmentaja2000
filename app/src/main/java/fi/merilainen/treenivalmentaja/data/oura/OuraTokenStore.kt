package fi.merilainen.treenivalmentaja.data.oura

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
 * Where Oura's tokens live, and the half-finished authorization that is on its way to becoming
 * some.
 *
 * An interface because the implementation below is the one part of this feature that cannot run off
 * a device — the Android Keystore has no JVM equivalent, so a unit test of anything that merely
 * *uses* a token store would otherwise need an emulator. The real implementation is covered by an
 * instrumented test instead, which is where a Keystore actually exists.
 */
internal interface OuraTokenStorage {

  suspend fun load(): OuraTokens?

  suspend fun save(tokens: OuraTokens)

  suspend fun clear()

  suspend fun hasTokens(): Boolean = load() != null

  suspend fun savePending(codeVerifier: String, state: String)

  suspend fun pendingVerifier(): String?

  suspend fun pendingState(): String?

  suspend fun clearPending()

  /**
   * The client credentials the user typed into Settings, or `null` if they never have.
   *
   * These are secrets and live under the same key as the tokens. They outlive a disconnect on
   * purpose: connecting again should not mean pasting a client id and secret a second time.
   */
  suspend fun credentials(): OuraCredentials?

  suspend fun saveCredentials(credentials: OuraCredentials)

  suspend fun clearCredentials()
}

/**
 * Oura's tokens on disk, encrypted with a key that cannot leave the device.
 *
 * **Why not `EncryptedSharedPreferences`,** which ADR-006 and `docs/AUTHENTICATION.md` originally
 * named: `androidx.security:security-crypto` was deprecated in April 2025 at `1.1.0-alpha07` and
 * receives no further fixes, including for the Keystore crash reported against it. Depending on an
 * abandoned library for the one security-critical store in the app is worse than using the platform
 * primitives it wraps. See ADR-008 in `docs/DECISIONS.md`.
 *
 * What it does is what that library does, minus Tink's key hierarchy: a 256-bit AES key generated
 * inside the Android Keystore — where it is not extractable, so a copy of this app's data directory
 * is worth nothing off the device — and AES-GCM, which authenticates as well as encrypts, so a
 * tampered ciphertext fails to decrypt rather than decrypting to something else. The IV is
 * generated per encryption by the Keystore itself (`setRandomizedEncryptionRequired` is on by
 * default) and stored beside the ciphertext, because reusing a GCM nonce is the one mistake that
 * breaks it completely.
 *
 * The tokens are also kept out of backups — see `res/xml/backup_rules.xml`. Restoring the
 * ciphertext onto a device whose Keystore has none of the key would leave the app holding bytes it
 * can never read; excluding them means a restored install simply asks to connect again.
 */
internal class OuraTokenStore(context: Context) : OuraTokenStorage {

  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** `null` when Oura has never been connected, or the stored bytes can no longer be read. */
  override suspend fun load(): OuraTokens? =
    withContext(Dispatchers.IO) {
      val access = decrypt(prefs.getString(KEY_ACCESS, null)) ?: return@withContext null
      OuraTokens(
        accessToken = access,
        refreshToken = decrypt(prefs.getString(KEY_REFRESH, null)),
        expiresAtUtc = prefs.getLong(KEY_EXPIRES_AT, OuraTokens.UNKNOWN_EXPIRY),
      )
    }

  override suspend fun save(tokens: OuraTokens) {
    withContext(Dispatchers.IO) {
      val access = encrypt(tokens.accessToken) ?: return@withContext
      prefs.edit(commit = true) {
        putString(KEY_ACCESS, access)
        // Removed rather than left behind: a connection that came back without a refresh token
        // must not keep the previous one, which belongs to a login that is over.
        val refresh = tokens.refreshToken?.let { encrypt(it) }
        if (refresh != null) putString(KEY_REFRESH, refresh) else remove(KEY_REFRESH)
        putLong(KEY_EXPIRES_AT, tokens.expiresAtUtc)
      }
    }
  }

  /**
   * The tokens and any half-finished authorization — **not** the client credentials.
   *
   * That distinction is the difference between disconnecting and starting over. Connecting again
   * after a disconnect must not mean pasting a client id and secret a second time, so this removes
   * named keys rather than calling `clear()` on the whole file. It did call `clear()` once, which
   * an instrumented test caught: the in-memory fake used by the unit tests kept the credentials, so
   * the unit test asserting they survive a disconnect passed while the real store wiped them.
   *
   * `commit` rather than `apply`: disconnecting is the one operation whose result the user is
   * entitled to see through, and a process death between the two would otherwise leave the tokens
   * on disk after the app said they were gone.
   */
  override suspend fun clear() {
    withContext(Dispatchers.IO) {
      prefs.edit(commit = true) {
        remove(KEY_ACCESS)
        remove(KEY_REFRESH)
        remove(KEY_EXPIRES_AT)
        remove(KEY_VERIFIER)
        remove(KEY_STATE)
      }
    }
  }

  // ------------------------------------------------------------------ the flow in progress

  /**
   * The PKCE verifier and the `state`, held between opening the browser and coming back.
   *
   * On disk rather than in memory, because the round trip leaves the app in the background where
   * the process may be killed — and a verifier lost that way turns a successful login into a
   * failed exchange. Encrypted with the same key: the verifier is the secret half of PKCE, and
   * writing it in the clear would undo the point of using PKCE at all.
   */
  override suspend fun savePending(codeVerifier: String, state: String) {
    withContext(Dispatchers.IO) {
      val verifier = encrypt(codeVerifier) ?: return@withContext
      prefs.edit(commit = true) {
        putString(KEY_VERIFIER, verifier)
        putString(KEY_STATE, state)
      }
    }
  }

  override suspend fun pendingVerifier(): String? =
    withContext(Dispatchers.IO) { decrypt(prefs.getString(KEY_VERIFIER, null)) }

  override suspend fun pendingState(): String? =
    withContext(Dispatchers.IO) { prefs.getString(KEY_STATE, null) }

  // ------------------------------------------------------------------ client credentials

  override suspend fun credentials(): OuraCredentials? =
    withContext(Dispatchers.IO) {
      val id = decrypt(prefs.getString(KEY_CLIENT_ID, null)) ?: return@withContext null
      val secret = decrypt(prefs.getString(KEY_CLIENT_SECRET, null)) ?: return@withContext null
      OuraCredentials(clientId = id, clientSecret = secret)
    }

  override suspend fun saveCredentials(credentials: OuraCredentials) {
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

  /** Called whether the redirect succeeded or failed: a verifier is good for one attempt. */
  override suspend fun clearPending() {
    withContext(Dispatchers.IO) {
      prefs.edit(commit = true) {
        remove(KEY_VERIFIER)
        remove(KEY_STATE)
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
      // [iv length][iv][ciphertext]: GCM's nonce is 12 bytes here, but the length is written down
      // rather than assumed, so a future provider choosing differently does not silently corrupt.
      val packed = ByteArray(1 + iv.size + encrypted.size)
      packed[0] = iv.size.toByte()
      iv.copyInto(packed, 1)
      encrypted.copyInto(packed, 1 + iv.size)
      Base64.getEncoder().encodeToString(packed)
    } catch (e: GeneralSecurityException) {
      null
    }

  /**
   * `null` for anything that cannot be read back, and that is a deliberate outcome rather than a
   * swallowed error: a Keystore key is gone after a factory reset, a restored backup, or the screen
   * lock being removed on some devices. The honest reading of "the tokens cannot be decrypted" is
   * "Oura is not connected", which asks the user to connect again — not a crash on startup.
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
      // Not base64 at all.
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
        // Deliberately not requiring user authentication: reminders and a background sync have to
        // work with the phone in a pocket, and a token the app cannot read while locked would make
        // both impossible.
        .setUserAuthenticationRequired(false)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val FILE = "oura_tokens"

    const val PROVIDER = "AndroidKeyStore"

    const val ALIAS = "treenivalmentaja.oura.tokens"

    const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val TAG_BITS = 128

    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_VERIFIER = "pending_verifier"
    const val KEY_STATE = "pending_state"
    const val KEY_CLIENT_ID = "client_id"
    const val KEY_CLIENT_SECRET = "client_secret"
  }
}
