package fi.merilainen.treenivalmentaja.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/**
 * Installs the published APK with Android's own [PackageInstaller], without the file ever becoming
 * a document the user has to find.
 *
 * The APK is streamed from HTTPS straight into an install session: no `DownloadManager`, no
 * browser, no public Downloads folder, and therefore no storage permission and nothing left behind
 * on the device if the install is declined. The session is Android's own staging area, and
 * abandoning it takes the bytes with it.
 *
 * What this class does *not* do is install anything quietly. `USER_ACTION_REQUIRED` is set
 * deliberately: the user sees Android's ordinary "Päivitetäänkö tämä sovellus?" dialog, which is
 * the same confirmation the old browser route reached, minus the download. Android checks the
 * package name and the signing certificate on top of the size and digest checked here — an APK
 * signed with another key cannot take this app's place whatever this code does.
 */
class PackageInstallerApkInstaller(
  private val context: Context,
  /**
   * How the bytes arrive. Injectable so a test can hand over a stream without a network, and
   * HTTPS-only by default — [HttpUpdateService.parseUpdateInfo] refuses a plain-HTTP URL, and this
   * is the second place that has to be true.
   */
  private val openApkStream: (String) -> InputStream = ::openHttpsStream,
) : ApkInstaller {

  /**
   * What [UpdateInstallReceiver] heard back from Android.
   *
   * A hot flow rather than a callback because the receiver is created by the system, not by this
   * class: the manifest entry is what makes the callback un-forgeable (`exported="false"`), and the
   * price of that is that the two halves can only meet through the application object. Buffered so
   * that a status arriving before the collector is scheduled is not lost.
   */
  private val statuses =
    MutableSharedFlow<InstallStatus>(
      extraBufferCapacity = 8,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  /** One status callback from `PackageInstaller`, for one session. */
  private data class InstallStatus(val sessionId: Int, val status: Int, val message: String?)

  override fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

  /** Called by [UpdateInstallReceiver], which is the only component Android delivers this to. */
  fun onInstallStatus(intent: Intent) {
    val sessionId =
      intent.getIntExtra(
        PackageInstaller.EXTRA_SESSION_ID,
        intent.getIntExtra(EXTRA_OWN_SESSION_ID, -1),
      )
    statuses.tryEmit(
      InstallStatus(
        sessionId = sessionId,
        status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
        message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
      )
    )
  }

  override fun downloadAndInstall(
    apkUrl: String,
    sizeBytes: Long,
    sha256: String,
  ): Flow<InstallProgress> =
    channelFlow {
        if (!canRequestInstall()) {
          send(InstallProgress.Failed("sovelluksella ei ole lupaa asentaa päivityksiä"))
          return@channelFlow
        }

        val installer = context.packageManager.packageInstaller
        val params =
          PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // Both are checked by Android as well as here: a session that names another package,
            // or whose contents do not add up to the declared size, is refused by the platform.
            setAppPackageName(context.packageName)
            setSize(sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
          }

        val sessionId =
          try {
            installer.createSession(params)
          } catch (e: IOException) {
            send(InstallProgress.Failed(reasonFor(e)))
            return@channelFlow
          }

        var committed = false
        try {
          val transfer =
            installer.openSession(sessionId).use { session ->
              val sink = session.openWrite(APK_SESSION_ENTRY, 0, sizeBytes)
              val result =
                try {
                  openApkStream(apkUrl).use { source ->
                    ApkTransfer.copyVerifying(
                      source = source,
                      sink = sink,
                      expectedSizeBytes = sizeBytes,
                      expectedSha256 = sha256,
                      onProgress = { percent ->
                        // Blocking rather than dropping: this runs on the IO dispatcher, and a
                        // percentage that stops moving reads as a stalled download.
                        trySendBlocking(InstallProgress.Downloading(percent))
                      },
                    )
                  }
                } finally {
                  // fsync before close, or the session may be committed over a partial file.
                  session.fsync(sink)
                  sink.close()
                }

              if (result is TransferResult.Verified) {
                session.commit(statusIntentFor(sessionId).intentSender)
                committed = true
              }
              result
            }

          if (transfer is TransferResult.Rejected) {
            // The bytes go with the session. Nothing unverified is left anywhere on the device.
            installer.abandonSession(sessionId)
            send(InstallProgress.Failed(transfer.reason))
            return@channelFlow
          }

          coroutineScope {
            // Subscribed before the wait is announced, not after: the callback can arrive while
            // this coroutine is still being dispatched, and a status flow has no replay.
            val outcome =
              async(start = CoroutineStart.UNDISPATCHED) {
                statuses.first {
                  it.sessionId == sessionId &&
                    it.status != PackageInstaller.STATUS_PENDING_USER_ACTION
                }
              }
            send(InstallProgress.AwaitingConfirmation)
            val status = outcome.await()
            send(
              if (status.status == PackageInstaller.STATUS_SUCCESS) InstallProgress.Succeeded
              else InstallProgress.Failed(failureMessage(status.status, status.message))
            )
          }
        } catch (e: IOException) {
          if (!committed) installer.abandonSession(sessionId)
          send(InstallProgress.Failed(reasonFor(e)))
        } catch (e: SecurityException) {
          if (!committed) installer.abandonSession(sessionId)
          send(InstallProgress.Failed(reasonFor(e)))
        }
      }
      .flowOn(Dispatchers.IO)

  /**
   * Where `PackageInstaller` reports back to.
   *
   * **Mutable on Android 12+, and explicit about its component.** Mutable because the platform
   * fills in the status extras — an immutable one would arrive empty, including the confirmation
   * intent the whole flow waits for. Safe to make mutable because the component is named: only
   * this app's own non-exported receiver can be started with it, so nothing outside the app can
   * borrow it to deliver a result somewhere else.
   */
  private fun statusIntentFor(sessionId: Int): PendingIntent {
    val callback =
      Intent(context, UpdateInstallReceiver::class.java).apply {
        action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
        putExtra(EXTRA_OWN_SESSION_ID, sessionId)
      }
    val flags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
    // The session id doubles as the request code so two sessions cannot share a PendingIntent.
    return PendingIntent.getBroadcast(context, sessionId, callback, flags)
  }

  companion object {
    /** The name of the single APK inside the install session. Never a path on the device. */
    private const val APK_SESSION_ENTRY = "treenivalmentaja-update.apk"

    /**
     * The session id as this app wrote it, read only if the platform's own extra is missing.
     * Belt and braces around the one value that decides which download a callback belongs to.
     */
    private const val EXTRA_OWN_SESSION_ID = "fi.merilainen.treenivalmentaja.SESSION_ID"

    /**
     * What each `PackageInstaller` status means to someone looking at the version card.
     *
     * `STATUS_FAILURE_CONFLICT` gets the longest answer because it is the one with a cause the
     * user can act on: it is what a build signed with a different key looks like, and the fix is
     * to remove the copy that is installed rather than to try again.
     */
    fun failureMessage(status: Int, message: String?): String {
      val explanation =
        when (status) {
          PackageInstaller.STATUS_FAILURE_ABORTED -> "Asennus peruttiin."
          PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Android esti asennuksen. Salli päivitys laitteen asetuksista ja yritä uudelleen."
          PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Päivitys on ristiriidassa asennetun version kanssa. Näin käy, kun asennettu " +
              "sovellus on allekirjoitettu eri avaimella — poista se ensin laitteelta."
          PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "Päivitys ei ole yhteensopiva tämän laitteen kanssa."
          PackageInstaller.STATUS_FAILURE_STORAGE ->
            "Laitteessa ei ole tarpeeksi tilaa päivitykselle."
          PackageInstaller.STATUS_FAILURE_INVALID -> "Ladattu paketti oli viallinen."
          else -> "Asennus epäonnistui."
        }
      // Android's own text is kept when there is one: it names the verifier or validator that
      // refused, which a generic sentence cannot.
      return if (message.isNullOrBlank()) explanation else "$explanation ($message)"
    }

    private fun reasonFor(error: Throwable): String =
      "päivityksen lataus keskeytyi: ${error.message ?: error.javaClass.simpleName}"

    /**
     * HTTPS only, and not merely by convention: the connection is required to be an
     * [HttpsURLConnection], so a redirect down to plain HTTP fails here rather than quietly
     * downloading an installable file over a connection anyone on the path could rewrite.
     */
    private fun openHttpsStream(url: String): InputStream {
      val connection = URL(url).openConnection()
      require(connection is HttpsURLConnection) { "APK-osoite ei ole HTTPS-osoite" }
      connection.connectTimeout = 15_000
      connection.readTimeout = 30_000
      connection.instanceFollowRedirects = true
      val code = connection.responseCode
      if (code != HttpsURLConnection.HTTP_OK) {
        connection.disconnect()
        error("lataus vastasi HTTP $code")
      }
      return connection.inputStream
    }
  }
}
