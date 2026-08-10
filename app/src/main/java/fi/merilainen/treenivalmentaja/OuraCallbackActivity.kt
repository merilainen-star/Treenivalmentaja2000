package fi.merilainen.treenivalmentaja

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch

/**
 * Receives `treenivalmentaja://oauth2callback` and hands it straight to [OuraConnection].
 *
 * A separate activity rather than an intent filter on `MainActivity`: this one is **exported**,
 * because a browser has to be able to start it, and keeping that surface to a component that does
 * nothing but forward one string is easier to reason about than giving the main screen a second
 * entry point and a `singleTask` launch mode it does not otherwise need.
 *
 * Exported does not mean trusted. Anything on the device can start this with any URI it likes, so
 * the data is not acted on here at all — it is passed to `OuraConnection.completeAuthorization`,
 * which refuses anything whose `state` is not the exact value this device generated for a login it
 * actually started. See `docs/SECURITY.md` § Exported Android Components.
 *
 * It draws nothing and finishes immediately, which returns the user to the app they left. The
 * theme is `Theme.Translucent.NoTitleBar` rather than `Theme.NoDisplay`: the latter crashes if an
 * activity ever reaches `onResume` without having finished, and one line of defence against a
 * future edit is cheaper than the crash report.
 */
class OuraCallbackActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handle(intent)
    finish()
  }

  /** For the case where the browser reuses an instance of this activity rather than a new one. */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handle(intent)
    finish()
  }

  private fun handle(intent: Intent?) {
    val uri = intent?.data?.toString()
    val app = application as TreenivalmentajaApplication
    // On the application scope rather than this activity's: the exchange is a network round trip
    // and this activity is finishing on the next line. A login must not be cancelled by the
    // screen that received it going away.
    app.applicationScope.launch { app.ouraConnection.completeAuthorization(uri) }
  }
}
