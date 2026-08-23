package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.ThemePreference

/**
 * Light, dark, or whatever the phone says.
 *
 * Radio buttons rather than a switch, and the same row shape the model list in [AnalysisCard] uses:
 * three options where one of them is "let the system decide" is not a two-state question, and a
 * switch would have had to drop that third answer — the one the app behaved as before this card
 * existed.
 *
 * The choice takes effect as it is tapped: the preference is read at the top of the composition, in
 * `MainActivity`, so this card recolours under the finger rather than after a restart.
 */
@Composable
fun ThemeCard(selected: ThemePreference, onSelect: (ThemePreference) -> Unit = {}) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "Ulkoasu",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )

      Text(
        text =
          "Valitse käytetäänkö vaaleaa vai tummaa teemaa. Järjestelmä-vaihtoehto vaihtaa teeman " +
            "silloin kun puhelin vaihtaa omansa.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Column(modifier = Modifier.selectableGroup()) {
        ThemePreference.entries.forEach { option ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .selectable(
                  selected = option == selected,
                  role = Role.RadioButton,
                  onClick = { onSelect(option) },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            // The row carries the click and the role; the button is drawn but not separately
            // focusable, so TalkBack announces the option once rather than twice.
            RadioButton(selected = option == selected, onClick = null)
            Column {
              Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
              Text(
                text = option.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}
