package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which model the AI analysis asks — provider included, since the model implies it.
 *
 * **DataStore, not the Keystore.** A model name is a preference, not a secret; putting it behind
 * encryption would imply otherwise, and the keys it sits beside in Settings genuinely are secrets.
 * It shares the `settings` DataStore with [NotificationSettingsStore] rather than opening a second
 * file; a separate class only because notification times and an AI model have nothing to do with
 * each other, and one class about both would be a class about nothing.
 *
 * The stored value is the **enum constant name**, not the model id. A provider can rename or retire
 * an id, and a stored preference should survive that rather than silently resetting to the default.
 */
class AnalysisSettingsStore(private val context: Context) {

  private val ANALYSIS_MODEL = stringPreferencesKey("analysis_model_name")

  val modelFlow: Flow<AnalysisModel> =
    context.dataStore.data.map { AnalysisModel.fromStored(it[ANALYSIS_MODEL]) }

  suspend fun setModel(model: AnalysisModel) {
    context.dataStore.edit { it[ANALYSIS_MODEL] = model.name }
  }
}
