package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AdvisorSettingsStore(private val context: Context) {
  private val constraintsKey = stringPreferencesKey("advisor_constraints")

  val constraintsFlow: Flow<String> = context.dataStore.data.map { it[constraintsKey].orEmpty() }

  suspend fun setConstraints(value: String) {
    context.dataStore.edit { it[constraintsKey] = value.trim().take(1_000) }
  }
}
