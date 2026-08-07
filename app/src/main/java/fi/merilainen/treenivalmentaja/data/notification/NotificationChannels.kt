package fi.merilainen.treenivalmentaja.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val WORKOUT_REMINDERS = "workout_reminders"

    fun createChannels(context: Context) {
        // No SDK_INT guard: notification channels arrived in API 26, which is now minSdk.
        val name = "Treenimuistutukset"
        val descriptionText = "Ilmoitukset tulevista treeneistä"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(WORKOUT_REMINDERS, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
