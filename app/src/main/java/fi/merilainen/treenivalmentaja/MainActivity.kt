package fi.merilainen.treenivalmentaja

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fi.merilainen.treenivalmentaja.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        var showSplash by remember { mutableStateOf(true) }
        Surface(modifier = Modifier.fillMaxSize()) {
            if (showSplash) {
                SplashScreen(onSplashFinished = { showSplash = false })
            } else {
                TreenivalmentajaApp()
            }
        }
      }
    }
  }
}
