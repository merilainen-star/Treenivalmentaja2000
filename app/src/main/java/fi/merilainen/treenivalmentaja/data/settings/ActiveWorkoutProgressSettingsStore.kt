package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutPosition
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutProgressStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The DataStore-backed [ActiveWorkoutProgressStore], in the same `settings` file as the
 * notification times, the AI model, the theme and the missed-session refusal.
 *
 * A preference rather than a row on the session: the position is where a person is *right now*,
 * not something the training history should carry. What the session did keep — the movements ticked
 * off, the skips, the RPE — is written to the completion event when the workout ends, and that has
 * not changed.
 */
class ActiveWorkoutProgressSettingsStore(private val context: Context) : ActiveWorkoutProgressStore {

  private val POSITION = stringPreferencesKey("active_workout_position")

  override suspend fun load(): ActiveWorkoutPosition? =
    context.dataStore.data.map { prefs -> ActiveWorkoutPosition.parse(prefs[POSITION]) }.first()

  override suspend fun save(position: ActiveWorkoutPosition) {
    context.dataStore.edit { it[POSITION] = position.encode() }
  }

  override suspend fun clear() {
    context.dataStore.edit { it.remove(POSITION) }
  }
}
