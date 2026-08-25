package fi.merilainen.treenivalmentaja.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import fi.merilainen.treenivalmentaja.domain.ThemePreference

private val DarkColorScheme =
  darkColorScheme(
    primary = BluePrimary,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = TextPrimaryDark,
    secondary = GreenAccent,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    tertiary = YellowAccent,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onTertiary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = BluePrimaryDark,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = GreenAccent,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    tertiary = YellowAccent,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = TextPrimaryLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight,
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
  content: @Composable () -> Unit,
) {
  // Deliberately not Material You: the Electric Blue palette is part of the app's identity and
  // both user-selectable schemes must render the same semantic roles on every supported device.
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
