package fi.merilainen.treenivalmentaja.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

open class ReminderScheduler(private val context: Context) {

    open fun schedule(sessionId: String, remindAtUtc: Long, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("SESSION_ID", sessionId)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            remindAtUtc,
            pendingIntent
        )
    }

    fun cancel(sessionId: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("SESSION_ID", sessionId)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        alarmManager.cancel(pendingIntent)
    }

    open fun cancelAll(requestCodes: List<Int>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        requestCodes.forEach { requestCode ->
            val intent = Intent(context, ReminderReceiver::class.java)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
            alarmManager.cancel(pendingIntent)
        }
    }
    
    companion object {
        const val REMINDER_WINDOW_DAYS = 7
    }
}
