package fi.merilainen.treenivalmentaja.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TreenivalmentajaApplication
        
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.rescheduleAlarmsUseCase.execute()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
