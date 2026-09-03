package fi.merilainen.treenivalmentaja

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.merilainen.treenivalmentaja.ui.theme.MyApplicationTheme
import fi.merilainen.treenivalmentaja.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      // The ViewModel is built here rather than inside TreenivalmentajaApp because the theme is
      // now one of the things it holds, and the theme has to wrap everything the app draws —
      // splash included. It is the same activity-scoped instance either way; it is passed down so
      // that there is one obvious place it comes from.
      val viewModel: WorkoutViewModel = viewModel(factory = WorkoutViewModel.Factory)
      val theme by viewModel.themePreference.collectAsStateWithLifecycle()
      val darkTheme = theme.resolveDarkTheme()
      val isInitializing by viewModel.isInitializing.collectAsStateWithLifecycle()

      // The system bars have to be told too, or they keep drawing for the phone's dark-mode
      // setting: "Vaalea" chosen on a dark phone would put white status-bar icons on a white
      // background and lose the clock. `enableEdgeToEdge` is called again rather than once in
      // `onCreate`, because the answer changes when the preference does.
      LaunchedEffect(darkTheme) {
        enableEdgeToEdge(
          statusBarStyle =
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
          navigationBarStyle =
            SystemBarStyle.auto(LIGHT_NAVIGATION_SCRIM, DARK_NAVIGATION_SCRIM) { darkTheme },
        )
      }

      MyApplicationTheme(theme = theme, darkTheme = darkTheme) {
        var showSplash by remember { mutableStateOf(true) }
        Surface(modifier = Modifier.fillMaxSize()) {
            if (showSplash) {
                SplashScreen(
                    isInitializing = isInitializing,
                    onSplashFinished = { showSplash = false },
                )
            } else {
                TreenivalmentajaApp(viewModel)
            }
        }
      }
    }
  }

  private companion object {
    /**
     * The scrims `enableEdgeToEdge()` uses for the navigation bar when it is not called with a
     * style of its own — copied so that passing a style keeps the platform's own appearance on the
     * API levels that still draw a bar, rather than quietly changing it.
     */
    val LIGHT_NAVIGATION_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val DARK_NAVIGATION_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
  }
}
