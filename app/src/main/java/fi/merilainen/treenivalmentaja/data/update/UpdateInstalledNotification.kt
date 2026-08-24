package fi.merilainen.treenivalmentaja.data.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fi.merilainen.treenivalmentaja.BuildConfig
import fi.merilainen.treenivalmentaja.MainActivity
import fi.merilainen.treenivalmentaja.R
import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels

/**
 * Says that the app has been replaced, and offers one tap back into it.
 *
 * **This is not an automatic restart, and there is no way to make it one.** Installing an update
 * kills the process being replaced — that is what an update is — and Android then refuses to let
 * the new one start an activity: background activity launches have been blocked since Android 10,
 * and a `BroadcastReceiver` handling `ACTION_MY_PACKAGE_REPLACED` is on none of the exemption
 * lists. Calling `startActivity` here would fail silently on every phone this app runs on, which
 * is worse than not calling it: the code would read as though the app relaunches itself.
 *
 * A notification is what the platform leaves: the app cannot come back on its own, but it can put
 * itself one tap away instead of leaving the user to find the icon and wonder whether the update
 * went through. Tapping it opens the app and clears the notification.
 *
 * It has a channel of its own rather than borrowing the workout reminders' one. Turning off
 * training reminders is a thing people do, and it must not silently take this with it — nor should
 * this be able to make a sound that a workout reminder's settings were supposed to govern.
 */
object UpdateInstalledNotification {

  /**
   * Distinct from the reminder ids, which are `session.id.hashCode()` — an `Int` from a hash can
   * be anything, so this is a value no session id is likely to produce rather than a value none
   * can. A collision would replace one notification with the other, not lose data.
   */
  private const val NOTIFICATION_ID = 0x0DDA7E

  /** Silently does nothing without the permission, exactly as `ReminderReceiver` does. */
  fun show(context: Context) {
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }

    val open =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
      }
    val pendingOpen =
      PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        open,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(context, NotificationChannels.APP_UPDATES)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Treenivalmentaja päivitetty")
        // The version is named because it is the one thing the user cannot check without opening
        // the app, and checking it is the reason they would open it after an update.
        .setContentText("Versio ${BuildConfig.VERSION_NAME} on asennettu. Avaa sovellus.")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(pendingOpen)
        .build()

    try {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
      // The permission was revoked between the check and the post. Nothing to do and nothing to
      // report: an update that installed correctly is not a failure because it went unannounced.
    }
  }
}
