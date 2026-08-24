package fi.merilainen.treenivalmentaja.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication

/**
 * Where `PackageInstaller` reports what became of an install session.
 *
 * **This is a receiver, and `exported="false"`, for a security reason rather than a structural
 * one.** The callback carries `Intent.EXTRA_INTENT` — an intent this app then *starts*. Routed
 * through the exported `MainActivity`, any application on the device could send one, and this app
 * would launch whatever it named while believing it was Android's install prompt. A non-exported
 * receiver started by an explicit `PendingIntent` can be reached by the system alone, so the only
 * intent that arrives here is the one the platform put in.
 *
 * It does two things and no more: it starts the confirmation Android asked for, and it passes the
 * status to the installer that is waiting on it. See `docs/SECURITY.md`.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_INSTALL_STATUS) return

    // Reported first, so the version card is already saying "waiting for confirmation" by the time
    // the system dialog appears over it.
    (context.applicationContext as? TreenivalmentajaApplication)
      ?.apkInstaller
      ?.onInstallStatus(intent)

    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return

    val confirmation =
      IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
    // NEW_TASK because a receiver has no task of its own to start an activity in.
    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  companion object {
    const val ACTION_INSTALL_STATUS = "fi.merilainen.treenivalmentaja.INSTALL_STATUS"
  }
}
