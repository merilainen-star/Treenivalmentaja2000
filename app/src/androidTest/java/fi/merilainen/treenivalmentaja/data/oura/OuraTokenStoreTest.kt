package fi.merilainen.treenivalmentaja.data.oura

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real token store, on a device.
 *
 * Instrumented rather than a unit test because there is no Android Keystore on the JVM, and the
 * Keystore is the entire point: the key is generated inside it and cannot be extracted, which is
 * what makes a copy of the app's data directory worthless off the device. Everything that merely
 * *uses* a store is tested against `FakeOuraTokenStorage` instead.
 */
@RunWith(AndroidJUnit4::class)
class OuraTokenStoreTest {

  private lateinit var context: Context
  private lateinit var store: OuraTokenStore

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    store = OuraTokenStore(context)
    runBlocking { store.clear() }
  }

  @Test
  fun tokens_survive_a_round_trip() = runBlocking {
    store.save(OuraTokens("access-1", "refresh-1", expiresAtUtc = 1_754_800_000_000L))

    val loaded = store.load()!!

    assertEquals("access-1", loaded.accessToken)
    assertEquals("refresh-1", loaded.refreshToken)
    assertEquals(1_754_800_000_000L, loaded.expiresAtUtc)
  }

  @Test
  fun an_empty_store_holds_nothing() = runBlocking {
    assertNull(store.load())
    assertFalse(store.hasTokens())
  }

  /** What actually lands on disk must not be the token itself. */
  @Test
  fun the_stored_bytes_are_not_the_token() = runBlocking {
    store.save(OuraTokens("super-secret-access-token", "refresh-1", 0L))

    val raw = context.getSharedPreferences("oura_tokens", Context.MODE_PRIVATE).all.values
      .joinToString(" ")

    assertFalse(raw, raw.contains("super-secret-access-token"))
    assertFalse(raw, raw.contains("refresh-1"))
  }

  /**
   * GCM's nonce may never be reused with the same key. The store lets the Keystore generate one per
   * encryption, so the same plaintext written twice must not produce the same ciphertext.
   */
  @Test
  fun the_same_token_encrypts_differently_each_time() = runBlocking {
    val prefs = context.getSharedPreferences("oura_tokens", Context.MODE_PRIVATE)
    store.save(OuraTokens("same-token", null, 0L))
    val first = prefs.getString("access_token", null)
    store.save(OuraTokens("same-token", null, 0L))
    val second = prefs.getString("access_token", null)

    assertNotEquals(first, second)
    assertEquals("same-token", store.load()!!.accessToken)
  }

  /** AES-GCM authenticates. A ciphertext someone edited fails to decrypt rather than decrypting. */
  @Test
  fun a_tampered_ciphertext_is_not_readable() = runBlocking {
    store.save(OuraTokens("access-1", "refresh-1", 0L))
    val prefs = context.getSharedPreferences("oura_tokens", Context.MODE_PRIVATE)
    val stored = prefs.getString("access_token", null)!!
    // Flip the last character to something else, keeping it valid base64.
    val tampered = stored.dropLast(1) + if (stored.last() == 'A') 'B' else 'A'
    prefs.edit().putString("access_token", tampered).commit()

    assertNull(store.load())
  }

  @Test
  fun garbage_in_the_preferences_is_not_a_crash() = runBlocking {
    context
      .getSharedPreferences("oura_tokens", Context.MODE_PRIVATE)
      .edit()
      .putString("access_token", "this is not base64 at all !!!")
      .commit()

    assertNull(store.load())
  }

  @Test
  fun a_connection_without_a_refresh_token_is_still_storable() = runBlocking {
    store.save(OuraTokens("access-1", refreshToken = null, expiresAtUtc = 0L))

    val loaded = store.load()!!

    assertEquals("access-1", loaded.accessToken)
    assertNull(loaded.refreshToken)
  }

  /** Saving over a connection that had one must not leave the previous refresh token behind. */
  @Test
  fun a_refresh_token_is_removed_when_the_new_tokens_have_none() = runBlocking {
    store.save(OuraTokens("access-1", "refresh-1", 0L))

    store.save(OuraTokens("access-2", refreshToken = null, expiresAtUtc = 0L))

    assertNull(store.load()!!.refreshToken)
  }

  @Test
  fun clearing_removes_the_tokens_and_any_pending_login() = runBlocking {
    store.save(OuraTokens("access-1", "refresh-1", 0L))
    store.savePending("verifier-1", "state-1")

    store.clear()

    assertNull(store.load())
    assertNull(store.pendingVerifier())
    assertNull(store.pendingState())
  }

  // ------------------------------------------------------------------ client credentials

  @Test
  fun credentials_survive_a_round_trip() = runBlocking {
    store.saveCredentials(OuraCredentials(clientId = "client-abc", clientSecret = "secret-xyz"))

    val loaded = store.credentials()!!

    assertEquals("client-abc", loaded.clientId)
    assertEquals("secret-xyz", loaded.clientSecret)
  }

  /** The client secret is a secret, and gets the same treatment as a token. */
  @Test
  fun the_stored_bytes_are_not_the_client_secret() = runBlocking {
    store.saveCredentials(OuraCredentials("client-abc", "super-secret-client-secret"))

    val raw = context.getSharedPreferences("oura_tokens", Context.MODE_PRIVATE).all.values
      .joinToString(" ")

    assertFalse(raw, raw.contains("super-secret-client-secret"))
  }

  /**
   * Disconnecting is not starting over. This is the test that caught the real thing: `clear()`
   * emptied the whole preferences file, so a disconnect silently took the credentials with it —
   * while the in-memory fake the unit tests use kept them, so the unit test asserting exactly this
   * passed anyway.
   */
  @Test
  fun disconnecting_keeps_the_client_credentials() = runBlocking {
    store.saveCredentials(OuraCredentials("client-abc", "secret-xyz"))
    store.save(OuraTokens("access-1", "refresh-1", 0L))

    store.clear()

    assertNull(store.load())
    assertEquals("client-abc", store.credentials()!!.clientId)
  }

  @Test
  fun forgetting_the_credentials_removes_them() = runBlocking {
    store.saveCredentials(OuraCredentials("client-abc", "secret-xyz"))

    store.clearCredentials()

    assertNull(store.credentials())
  }

  /** Half-written credentials are no credentials: both fields are needed to return anything. */
  @Test
  fun credentials_with_only_an_id_read_back_as_none() = runBlocking {
    store.saveCredentials(OuraCredentials("client-abc", "secret-xyz"))
    context
      .getSharedPreferences("oura_tokens", Context.MODE_PRIVATE)
      .edit()
      .remove("client_secret")
      .commit()

    assertNull(store.credentials())
  }

  // ------------------------------------------------------------------ the flow in progress

  /** The verifier is the secret half of PKCE; storing it in the clear would undo the point. */
  @Test
  fun the_pending_verifier_is_encrypted_too() = runBlocking {
    store.savePending("the-verifier", "the-state")

    val raw = context.getSharedPreferences("oura_tokens", Context.MODE_PRIVATE)
      .getString("pending_verifier", null)!!

    assertFalse(raw, raw.contains("the-verifier"))
    assertEquals("the-verifier", store.pendingVerifier())
  }

  @Test
  fun the_pending_login_survives_a_new_store_instance() = runBlocking {
    store.savePending("the-verifier", "the-state")

    // What a process death during the browser round trip looks like from here.
    val afterRestart = OuraTokenStore(context)

    assertEquals("the-verifier", afterRestart.pendingVerifier())
    assertEquals("the-state", afterRestart.pendingState())
  }

  @Test
  fun clearing_the_pending_login_leaves_the_tokens_alone() = runBlocking {
    store.save(OuraTokens("access-1", "refresh-1", 0L))
    store.savePending("verifier-1", "state-1")

    store.clearPending()

    assertNull(store.pendingVerifier())
    assertTrue(store.hasTokens())
  }
}
