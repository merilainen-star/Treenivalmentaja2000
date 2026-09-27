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
    primary = Color(0xFF55D9ED),
    primaryContainer = Color(0xFF123B49),
    onPrimaryContainer = TextPrimaryDark,
    secondary = GreenAccent,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    tertiary = Color(0xFFC3AAFF),
    tertiaryContainer = Color(0xFF30264D),
    onTertiaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF080F19),
    surface = Color(0xFF101B29),
    surfaceContainerLow = Color(0xFF101B29),
    surfaceContainer = Color(0xFF172536),
    surfaceContainerHigh = Color(0xFF223247),
    surfaceVariant = Color(0xFF172536),
    outline = Color(0xFF8B9DB2),
    onPrimary = Color(0xFF003640),
    onSecondary = TextPrimaryDark,
    onTertiary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF3D6749),
    primaryContainer = Color(0xFFD9EBD4),
    onPrimaryContainer = Color(0xFF183721),
    secondary = GreenAccent,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    tertiary = Color(0xFF6F539F),
    tertiaryContainer = Color(0xFFEEE3FF),
    onTertiaryContainer = Color(0xFF33204F),
    background = Color(0xFFFAFAF5),
    surface = Color(0xFFFEFEF9),
    surfaceContainerLow = Color(0xFFF3F5ED),
    surfaceContainer = Color(0xFFEBEFE4),
    surfaceContainerHigh = Color(0xFFE1E7DB),
    surfaceVariant = Color(0xFFEBEFE4),
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
  // User-approved sage/lavender and midnight/cyan concepts share semantic roles. Fixed palettes
  // keep the selected visual design consistent across Android versions and wallpapers.
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
