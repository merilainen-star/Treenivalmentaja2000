package fi.merilainen.treenivalmentaja.data.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import fi.merilainen.treenivalmentaja.MainActivity

object AnalysisNotification {
  const val SESSION_EXTRA = "analysis_session_id"
  fun show(context: Context, sessionId: String) {
    if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val intent = Intent(context, MainActivity::class.java)
      .setData("treenivalmentaja://analysis/${Uri.encode(sessionId)}".toUri())
      .putExtra(SESSION_EXTRA, sessionId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, NotificationChannels.ANALYSES)
      .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Juoksun AI-analyysi on valmis")
      .setContentText("Avaa harjoituksen analyysi").setContentIntent(pending).setAutoCancel(true)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    NotificationManagerCompat.from(context).notify("analysis:$sessionId", 1, notification)
  }
}
