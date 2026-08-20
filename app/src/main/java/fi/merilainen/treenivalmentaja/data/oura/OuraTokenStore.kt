package fi.merilainen.treenivalmentaja.data.oura

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import fi.merilainen.treenivalmentaja.data.security.EncryptionResult
import fi.merilainen.treenivalmentaja.data.security.KeystoreCipher
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

  suspend fun save(tokens: OuraTokens): CredentialSaveResult

  suspend fun clear()

  suspend fun hasTokens(): Boolean = load() != null

  suspend fun savePending(codeVerifier: String, state: String): CredentialSaveResult

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

  suspend fun saveCredentials(credentials: OuraCredentials): CredentialSaveResult

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
  private val cipher = KeystoreCipher(ALIAS)

  /** `null` when Oura has never been connected, or the stored bytes can no longer be read. */
  override suspend fun load(): OuraTokens? =
    withContext(Dispatchers.IO) {
      val access = cipher.decrypt(prefs.getString(KEY_ACCESS, null)) ?: return@withContext null
      OuraTokens(
        accessToken = access,
        refreshToken = cipher.decrypt(prefs.getString(KEY_REFRESH, null)),
        expiresAtUtc = prefs.getLong(KEY_EXPIRES_AT, OuraTokens.UNKNOWN_EXPIRY),
      )
    }

  override suspend fun save(tokens: OuraTokens): CredentialSaveResult =
    withContext(Dispatchers.IO) {
      val access = (cipher.encrypt(tokens.accessToken) as? EncryptionResult.Success)?.encoded
        ?: return@withContext CredentialSaveResult.StorageFailure
      val refresh =
        tokens.refreshToken?.let {
          (cipher.encrypt(it) as? EncryptionResult.Success)?.encoded
            ?: return@withContext CredentialSaveResult.StorageFailure
        }
      val editor =
        prefs.edit().putString(KEY_ACCESS, access).putLong(KEY_EXPIRES_AT, tokens.expiresAtUtc)
      if (refresh != null) editor.putString(KEY_REFRESH, refresh) else editor.remove(KEY_REFRESH)
      if (editor.commit()) CredentialSaveResult.Success else CredentialSaveResult.StorageFailure
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
  override suspend fun savePending(
    codeVerifier: String,
    state: String,
  ): CredentialSaveResult =
    withContext(Dispatchers.IO) {
      val verifier = (cipher.encrypt(codeVerifier) as? EncryptionResult.Success)?.encoded
        ?: return@withContext CredentialSaveResult.StorageFailure
      val saved = prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).commit()
      if (saved) CredentialSaveResult.Success else CredentialSaveResult.StorageFailure
    }

  override suspend fun pendingVerifier(): String? =
    withContext(Dispatchers.IO) { cipher.decrypt(prefs.getString(KEY_VERIFIER, null)) }

  override suspend fun pendingState(): String? =
    withContext(Dispatchers.IO) { prefs.getString(KEY_STATE, null) }

  // ------------------------------------------------------------------ client credentials

  override suspend fun credentials(): OuraCredentials? =
    withContext(Dispatchers.IO) {
      val id = cipher.decrypt(prefs.getString(KEY_CLIENT_ID, null)) ?: return@withContext null
      val secret = cipher.decrypt(prefs.getString(KEY_CLIENT_SECRET, null)) ?: return@withContext null
      OuraCredentials(clientId = id, clientSecret = secret)
    }

  override suspend fun saveCredentials(credentials: OuraCredentials): CredentialSaveResult =
    withContext(Dispatchers.IO) {
      val id = (cipher.encrypt(credentials.clientId) as? EncryptionResult.Success)?.encoded
        ?: return@withContext CredentialSaveResult.StorageFailure
      val secret = (cipher.encrypt(credentials.clientSecret) as? EncryptionResult.Success)?.encoded
        ?: return@withContext CredentialSaveResult.StorageFailure
      val saved =
        prefs.edit().putString(KEY_CLIENT_ID, id).putString(KEY_CLIENT_SECRET, secret).commit()
      if (saved) CredentialSaveResult.Success else CredentialSaveResult.StorageFailure
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

  private companion object {
    const val FILE = "oura_tokens"

    const val ALIAS = "treenivalmentaja.oura.tokens"

    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_VERIFIER = "pending_verifier"
    const val KEY_STATE = "pending_state"
    const val KEY_CLIENT_ID = "client_id"
    const val KEY_CLIENT_SECRET = "client_secret"
  }
}
