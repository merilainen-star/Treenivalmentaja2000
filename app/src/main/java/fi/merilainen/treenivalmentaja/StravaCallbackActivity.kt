package fi.merilainen.treenivalmentaja

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch

/**
 * Receives `treenivalmentaja://localhost/strava` and hands it straight to `StravaConnection`.
 *
 * A separate exported activity for the reason [OuraCallbackActivity] is one, and the same
 * security posture: anything on the device can start this with any URI it likes, so the data is
 * not acted on here at all — `completeAuthorization` refuses anything whose `state` is not the
 * exact value this device generated. See `docs/SECURITY.md` § Exported Android Components.
 *
 * The host is `localhost` because Strava validates the redirect's host against the application's
 * "Authorization Callback Domain", and `localhost` is what that field accepts for an app with no
 * web domain — see `StravaOAuth.REDIRECT_URI`.
 */
class StravaCallbackActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handle(intent)
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
    finish()
  }

  private fun handle(intent: Intent?) {
    val uri = intent?.data?.toString()
    val app = application as TreenivalmentajaApplication
    // On the application scope: the exchange is a network round trip and this activity is
    // finishing on the next line.
    app.applicationScope.launch { app.stravaConnection.completeAuthorization(uri) }
  }
}
