package fi.merilainen.treenivalmentaja.data.alarm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fi.merilainen.treenivalmentaja.MainActivity
import fi.merilainen.treenivalmentaja.R
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import fi.merilainen.treenivalmentaja.data.notification.NotificationChannels
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra("SESSION_ID") ?: return
        val app = context.applicationContext as TreenivalmentajaApplication

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (sessionId == "REARM") {
                    app.rescheduleAlarmsUseCase.execute()
                    return@launch
                }

                val session = app.repository.getSession(sessionId) ?: return@launch
                if (session.status != SessionStatus.PLANNED) return@launch

                app.repository.transition(sessionId, SessionStatus.NOTIFIED, EventSource.ALARM)
                showNotification(context, session)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(context: Context, session: TrainingSession) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            session.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = session.type.title
        val text = if (session.description != null) "${session.description} (${session.durationMin} min)" else "${session.durationMin} min"


        val postponeIntent = Intent(context, ActionReceiver::class.java).apply {
            action = "fi.merilainen.treenivalmentaja.ACTION_POSTPONE"
            putExtra("SESSION_ID", session.id)
        }
        val pendingPostpone = PendingIntent.getBroadcast(
            context,
            session.id.hashCode(),
            postponeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.WORKOUT_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_launcher_foreground, "Aloita", pendingOpen)
            .addAction(R.drawable.ic_launcher_foreground, "Siirrä huomiselle", pendingPostpone)
            .build()


        try {
            NotificationManagerCompat.from(context).notify(session.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // Ignored
        }
    }
}
