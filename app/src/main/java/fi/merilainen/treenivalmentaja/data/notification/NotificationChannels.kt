package fi.merilainen.treenivalmentaja.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationChannels {
    const val WORKOUT_REMINDERS = "workout_reminders"

    /**
     * Separate from [WORKOUT_REMINDERS] on purpose. Turning off training reminders is a thing
     * people do, and it must not silently take the "the app was updated" notice with it — nor
     * should that notice be able to make a sound the reminder channel's settings were meant to
     * govern. Default importance, because it announces something that has already happened.
     */
    const val APP_UPDATES = "app_updates"

    fun createChannels(context: Context) {
        // No SDK_INT guard: notification channels arrived in API 26, which is now minSdk.
        val reminders = NotificationChannel(
            WORKOUT_REMINDERS,
            "Treenimuistutukset",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Ilmoitukset tulevista treeneistä" }

        val updates = NotificationChannel(
            APP_UPDATES,
            "Sovelluksen päivitykset",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Ilmoitus siitä, että sovellus on päivitetty ja voi avata sen" }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(reminders)
        notificationManager.createNotificationChannel(updates)
    }
}
