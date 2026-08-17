package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.merilainen.treenivalmentaja.domain.AnthropicModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which model the AI analysis asks.
 *
 * **DataStore, not the Keystore.** A model id is a preference, not a secret — putting it behind
 * encryption would imply it were one, and the key it sits beside in Settings genuinely is. It shares
 * the `settings` DataStore with [NotificationSettingsStore] rather than opening a second file; a
 * separate class only because notification times and an AI model have nothing to do with each other
 * and one class about both would be a class about nothing.
 */
class AnalysisSettingsStore(private val context: Context) {

  private val ANALYSIS_MODEL = stringPreferencesKey("analysis_model")

  /**
   * The chosen model, defaulting to [AnthropicModel.DEFAULT].
   *
   * An id no longer in the enum — one dropped from the list by a later build — also reads as the
   * default rather than failing, so a stale stored preference cannot stop the screen from drawing.
   */
  val modelFlow: Flow<AnthropicModel> =
    context.dataStore.data.map { AnthropicModel.fromId(it[ANALYSIS_MODEL]) }

  suspend fun setModel(model: AnthropicModel) {
    context.dataStore.edit { it[ANALYSIS_MODEL] = model.id }
  }
}
