package fi.merilainen.treenivalmentaja.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra("SESSION_ID") ?: return
        val action = intent.action ?: return
        
        val app = context.applicationContext as TreenivalmentajaApplication
        
        if (action == "fi.merilainen.treenivalmentaja.ACTION_POSTPONE") {
            NotificationManagerCompat.from(context).cancel(sessionId.hashCode())
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val tomorrow = LocalDate.now(ZoneId.of("Europe/Helsinki")).plusDays(1)
                    app.repository.reschedule(sessionId, tomorrow, null)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
