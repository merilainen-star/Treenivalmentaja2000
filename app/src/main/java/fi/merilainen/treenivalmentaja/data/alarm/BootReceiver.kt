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
        // Alarms do not survive a reboot, a reinstall or a time-zone change, so each of these
        // three actions has to re-arm them. Anything else is not ours to act on: the manifest
        // filter is the contract, and an intent outside it must not trigger a reschedule.
        if (intent.action !in HANDLED_ACTIONS) return

        val app = context.applicationContext as TreenivalmentajaApplication

        val pendingResult: android.content.BroadcastReceiver.PendingResult? = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.rescheduleAlarmsUseCase.execute()
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        /** Must stay in sync with the `intent-filter` for this receiver in `AndroidManifest.xml`. */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
