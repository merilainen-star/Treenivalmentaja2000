package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.merilainen.treenivalmentaja.domain.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Whether the app draws itself light, dark, or the way the phone asks.
 *
 * It shares the `settings` DataStore with [NotificationSettingsStore] and [AnalysisSettingsStore]
 * for the reason those two share it with each other: one preferences file is enough, and a class
 * per subject keeps each of them about something. Nothing here is a secret — a colour scheme is a
 * preference, and putting it behind the Keystore would imply otherwise.
 *
 * The stored value is the enum constant name; see [ThemePreference.fromStored] for why, and for
 * what an unrecognised one does.
 */
class ThemeSettingsStore(private val context: Context) {

  private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")

  val themeFlow: Flow<ThemePreference> =
    context.dataStore.data.map { ThemePreference.fromStored(it[THEME_PREFERENCE]) }

  suspend fun setTheme(preference: ThemePreference) {
    context.dataStore.edit { it[THEME_PREFERENCE] = preference.name }
  }
}
