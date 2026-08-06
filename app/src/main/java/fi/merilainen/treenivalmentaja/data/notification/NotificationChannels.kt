package fi.merilainen.treenivalmentaja.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val WORKOUT_REMINDERS = "workout_reminders"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
}
