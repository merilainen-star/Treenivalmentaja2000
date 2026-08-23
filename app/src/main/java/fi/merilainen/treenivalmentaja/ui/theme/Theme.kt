package fi.merilainen.treenivalmentaja.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import fi.merilainen.treenivalmentaja.domain.ThemePreference

private val DarkColorScheme =
  darkColorScheme(
    primary = BluePrimary,
    secondary = GreenAccent,
    tertiary = YellowAccent,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onTertiary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = BluePrimaryDark,
    secondary = GreenAccent,
    tertiary = YellowAccent,
    background = BackgroundLight,
    surface = SurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = TextPrimaryLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
  )

/**
 * What [ThemePreference.SYSTEM] resolves to right now — and what the other two ignore it for.
 *
 * Composable rather than a plain `when`, because [isSystemInDarkTheme] reads a configuration that
 * changes under a running app: a phone switching to dark at sunset recomposes through this, so the
 * app follows it without being restarted.
 */
@Composable
fun ThemePreference.resolveDarkTheme(): Boolean =
  when (this) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
  }

/**
 * @param theme what the user chose in Settings. It only decides [darkTheme]; pass [darkTheme]
 *   directly to pin a scheme regardless of the preference, which is what the screenshot tests do.
 */
@Composable
fun MyApplicationTheme(
  theme: ThemePreference = ThemePreference.DEFAULT,
  darkTheme: Boolean = theme.resolveDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
