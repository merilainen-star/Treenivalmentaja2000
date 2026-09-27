package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.map

data class AutomationSettings(
  val completeRuns: Boolean = true,
  val analyseRuns: Boolean = true,
  val exportRuns: Boolean = true,
)

class AutomationSettingsStore(private val context: Context) {
  private val complete = booleanPreferencesKey("auto_complete_runs")
  private val analyse = booleanPreferencesKey("auto_analyse_runs")
  private val export = booleanPreferencesKey("auto_export_runs")
  private val exportMessage = stringPreferencesKey("auto_export_message")
  private val signature = stringPreferencesKey("auto_export_signature")
  val exportSignature = context.dataStore.data.map { it[signature] }
  suspend fun markExported(value: String) { context.dataStore.edit { it[signature] = value } }
  val settings = context.dataStore.data.map {
    AutomationSettings(it[complete] ?: true, it[analyse] ?: true, it[export] ?: true)
  }
  val lastExport = context.dataStore.data.map { it[exportMessage] }
  suspend fun set(value: AutomationSettings) { context.dataStore.edit {
    it[complete] = value.completeRuns; it[analyse] = value.analyseRuns; it[export] = value.exportRuns
  } }
  suspend fun reportExport(message: String) { context.dataStore.edit { it[exportMessage] = message } }
}
