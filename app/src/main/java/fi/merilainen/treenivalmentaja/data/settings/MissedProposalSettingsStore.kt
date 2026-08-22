package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fi.merilainen.treenivalmentaja.domain.MissedProposalDismissalStore
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The DataStore-backed [MissedProposalDismissalStore].
 *
 * It shares the `settings` DataStore with the notification times and the AI model for the reason
 * they share it with each other: one preferences file is enough, and a separate class per subject
 * keeps each of them about something. An ISO date string is stored, and anything unparseable reads
 * as "never refused" — a corrupted preference should ask again, not silence the card forever.
 */
class MissedProposalSettingsStore(private val context: Context) : MissedProposalDismissalStore {

  private val DISMISSED_FOR = stringPreferencesKey("missed_proposal_dismissed_for")

  override suspend fun dismissedFor(): LocalDate? =
    context.dataStore.data
      .map { prefs -> prefs[DISMISSED_FOR]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
      .first()

  override suspend fun setDismissedFor(date: LocalDate) {
    context.dataStore.edit { it[DISMISSED_FOR] = date.toString() }
  }
}
