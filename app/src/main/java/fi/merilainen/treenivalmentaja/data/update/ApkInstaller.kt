package fi.merilainen.treenivalmentaja.data.update

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/** How far the update has got. Every value here is something the version card can say out loud. */
sealed interface InstallProgress {
  /** [percent] is of the published byte count, so it reaches 100 only when the file is whole. */
  data class Downloading(val percent: Int) : InstallProgress

  /** The APK is downloaded, verified and committed; Android is asking the user to confirm. */
  data object AwaitingConfirmation : InstallProgress

  /** Android installed it. Rarely seen: replacing this app usually kills the process first. */
  data object Succeeded : InstallProgress

  data class Failed(val reason: String) : InstallProgress
}

/**
 * Downloads the published APK and hands it to Android's own installer.
 *
 * An interface for the reason [UpdateService] is one: everything below it needs a
 * `PackageInstaller`, and a use case that could not be built without one would make every test of
 * the update flow an instrumented test.
 */
interface ApkInstaller {
  /**
   * Whether this app is allowed to ask Android to install a package at all — the
   * "Asenna tuntemattomia sovelluksia" toggle, which is per-app from Android 8 onwards.
   */
  fun canRequestInstall(): Boolean

  /**
   * Streams the APK straight into an install session, verifies it, and commits it.
   *
   * The published size and digest are arguments rather than being read from the file afterwards:
   * they are what the download is checked *against*, and a digest computed from the bytes that
   * arrived would only ever agree with itself.
   */
  fun downloadAndInstall(apkUrl: String, sizeBytes: Long, sha256: String): Flow<InstallProgress>
}

/** Whether the bytes that arrived are the bytes that were published. */
sealed interface TransferResult {
  data object Verified : TransferResult

  data class Rejected(val reason: String) : TransferResult
}

/**
 * The copy step, with the integrity check built into it.
 *
 * Pure input/output streams and no Android type, so the one part of the install that can silently
 * do the wrong thing — accept a file that is not the published one — is covered by an ordinary
 * unit test. The digest is computed *while* copying rather than by re-reading afterwards: the
 * bytes are going into a `PackageInstaller` session that cannot be read back.
 */
object ApkTransfer {

  private const val BUFFER_BYTES = 64 * 1024

  fun copyVerifying(
    source: InputStream,
    sink: OutputStream,
    expectedSizeBytes: Long,
    expectedSha256: String,
    onProgress: (Int) -> Unit = {},
  ): TransferResult {
    // Refused rather than divided by: the percentage below would throw, and a release that
    // declares no size is one there is nothing to verify against in the first place.
    if (expectedSizeBytes <= 0) {
      return TransferResult.Rejected("julkaisu ei kerro APK:n kokoa")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_BYTES)
    var written = 0L
    var lastPercent = -1

    while (true) {
      val read = source.read(buffer)
      if (read == -1) break
      digest.update(buffer, 0, read)
      sink.write(buffer, 0, read)
      written += read
      // A stream longer than the release said it would be is stopped here rather than after it
      // has filled the device: the size is already known to be wrong.
      if (written > expectedSizeBytes) break
      val percent = ((written * 100) / expectedSizeBytes).toInt().coerceIn(0, 100)
      if (percent != lastPercent) {
        lastPercent = percent
        onProgress(percent)
      }
    }
    sink.flush()

    if (written != expectedSizeBytes) {
      return TransferResult.Rejected(
        "latauksen koko ei täsmää julkaisuun ($written / $expectedSizeBytes tavua)"
      )
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(expectedSha256, ignoreCase = true)) {
      return TransferResult.Rejected("ladatun tiedoston tarkistussumma ei täsmää julkaisuun")
    }
    return TransferResult.Verified
  }
}
