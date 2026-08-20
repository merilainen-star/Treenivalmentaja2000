package fi.merilainen.treenivalmentaja.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A result the connection layer can surface instead of pretending a failed secure write worked. */
sealed interface CredentialSaveResult {
  data object Success : CredentialSaveResult
  data object InvalidInput : CredentialSaveResult
  data object StorageFailure : CredentialSaveResult
}

internal sealed interface EncryptionResult {
  data class Success(val encoded: String) : EncryptionResult
  data object Failure : EncryptionResult
}

/**
 * The shared Android Keystore AES-GCM primitive used by each credential store.
 *
 * Stores still use separate aliases and preferences files. This class only centralises the
 * security-sensitive packing, key creation and error handling so those copies cannot drift.
 */
internal class KeystoreCipher(private val alias: String) {

  fun encrypt(plain: String): EncryptionResult =
    try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, secretKey())
      val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      val iv = cipher.iv
      val packed = ByteArray(1 + iv.size + encrypted.size)
      packed[0] = iv.size.toByte()
      iv.copyInto(packed, 1)
      encrypted.copyInto(packed, 1 + iv.size)
      EncryptionResult.Success(Base64.getEncoder().encodeToString(packed))
    } catch (_: GeneralSecurityException) {
      EncryptionResult.Failure
    } catch (_: ProviderException) {
      EncryptionResult.Failure
    } catch (_: IOException) {
      EncryptionResult.Failure
    }

  /** Unreadable, corrupted or restored ciphertext is equivalent to a missing credential. */
  fun decrypt(stored: String?): String? {
    if (stored.isNullOrEmpty()) return null
    return try {
      val packed = Base64.getDecoder().decode(stored)
      if (packed.isEmpty()) return null
      val ivSize = packed[0].toInt()
      if (ivSize <= 0 || packed.size <= 1 + ivSize) return null
      val iv = packed.copyOfRange(1, 1 + ivSize)
      val encrypted = packed.copyOfRange(1 + ivSize, packed.size)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
      String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
      null
    } catch (_: ProviderException) {
      null
    } catch (_: IOException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
      return it.secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(
          alias,
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
    const val PROVIDER = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
  }
}
