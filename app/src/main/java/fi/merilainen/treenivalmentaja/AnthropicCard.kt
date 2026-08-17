package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.data.anthropic.AnthropicConnectionState
import fi.merilainen.treenivalmentaja.domain.AnthropicModel

/**
 * The Anthropic key and the model choice.
 *
 * Two states rather than the intervals.icu card's five, because saving a key here does not test it:
 * every call costs the owner money, and spending it to validate a paste nobody asked to spend money
 * on is the wrong default. The first "AI-analyysi" tap is the test — which is also why this card
 * says so in as many words rather than leaving the absence of a "Testaa yhteys" button to be noticed.
 *
 * The key is never redisplayed once saved, for the same reason the intervals.icu one is not: a field
 * that echoes a stored secret is a way for it to reach a screenshot or a shoulder, and nothing here
 * needs it read back.
 */
@Composable
fun AnthropicCard(
  state: AnthropicConnectionState,
  model: AnthropicModel,
  onSaveApiKey: (String) -> Unit = {},
  onClearApiKey: () -> Unit = {},
  onModelChange: (AnthropicModel) -> Unit = {},
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "AI-analyysi",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )

      Text(
        text =
          "Analyysi lähettää harjoituksen tiedot ja palautumislukemat Anthropicille ja palauttaa " +
            "valmentajan kommentin. Jokainen analyysi maksaa oman avaimesi kautta. Painike näkyy " +
            "vain viime viikon tehdyissä ja lähipäivien tulevissa harjoituksissa.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      when (state) {
        AnthropicConnectionState.NotConfigured -> KeyNeeded(onSaveApiKey)
        AnthropicConnectionState.Configured -> KeyStored(onClearApiKey)
      }

      // Offered even without a key: choosing the model is free, and the choice is part of
      // understanding what the key will be spent on.
      HorizontalDivider()
      ModelChooser(model, onModelChange)
    }
  }
}

/** The one-off setup, done on the phone — same shape as the intervals.icu key field. */
@Composable
private fun KeyNeeded(onSave: (String) -> Unit) {
  var apiKey by rememberSaveable { mutableStateOf("") }

  Text(
    text =
      "1. Luo API-avain osoitteessa console.anthropic.com — se toimii puhelimen selaimessa.\n" +
        "2. Kopioi avain alle.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  OutlinedTextField(
    value = apiKey,
    onValueChange = { apiKey = it },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    label = { Text("API-avain") },
    visualTransformation = PasswordVisualTransformation(),
  )
  Button(
    onClick = { onSave(apiKey) },
    modifier = Modifier.fillMaxWidth(),
    enabled = apiKey.isNotBlank(),
  ) {
    Text("Tallenna avain")
  }
}

@Composable
private fun KeyStored(onClear: () -> Unit) {
  Text(
    text = "API-avain on tallennettu.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  // Said explicitly, because the missing "Testaa yhteys" button next to the intervals.icu card's
  // one would otherwise look like an oversight rather than a decision.
  Text(
    text =
      "Avainta ei kokeilla erikseen, koska jokainen kutsu maksaa. Ensimmäinen AI-analyysi kertoo " +
        "toimiiko se.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  TextButton(
    onClick = onClear,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
  ) {
    Text("Poista avain")
  }
}

/**
 * Which model to spend.
 *
 * A fixed list of three rather than a text field: a mistyped model id is a `404` at tap time, on a
 * phone, indistinguishable from a broken key by anyone not reading the source. Radio buttons cannot
 * produce that. Each option carries what it costs, because that is the only thing that makes the
 * choice a real one.
 */
@Composable
private fun ModelChooser(selected: AnthropicModel, onChange: (AnthropicModel) -> Unit) {
  Text(text = "Malli", style = MaterialTheme.typography.titleMedium)
  Text(
    text = "Kalliimpi malli harkitsee pidempään. Kokeile, kumpi vastaa sinun dataasi paremmin.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Column {
    AnthropicModel.entries.forEach { option ->
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .selectable(
              selected = option == selected,
              role = Role.RadioButton,
              onClick = { onChange(option) },
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
