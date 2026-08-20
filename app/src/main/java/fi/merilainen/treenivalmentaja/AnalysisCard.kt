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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider

/**
 * The AI analysis: which model to ask, and the keys for the providers behind them.
 *
 * **Model first, keys second**, which is the opposite of how the Oura and intervals.icu cards are
 * laid out — those start with the credential because there is only one thing to connect to. Here the
 * model is the decision the owner actually makes, and it determines which key matters; showing three
 * key fields first would ask them to set up providers they will not use.
 *
 * No provider is tested when its key is saved. Every call costs money, so the first "AI-analyysi"
 * tap is the test — see [fi.merilainen.treenivalmentaja.data.analysis.AnalysisConnection].
 *
 * Keys are never redisplayed once saved, the same as the intervals.icu one: a field that echoes a
 * stored secret is a way for it to reach a screenshot or a shoulder, and nothing here needs it read
 * back.
 */
@Composable
fun AnalysisCard(
  configured: Set<AnalysisProvider>,
  saveFailure: AnalysisProvider? = null,
  model: AnalysisModel,
  onModelChange: (AnalysisModel) -> Unit = {},
  onSaveApiKey: (AnalysisProvider, String) -> Unit = { _, _ -> },
  onClearApiKey: (AnalysisProvider) -> Unit = {},
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
          "Analyysi lähettää yhden harjoituksen tiedot ja noin viikon palautumislukemat valitulle " +
            "palveluntarjoajalle. Jokainen analyysi maksaa oman avaimesi kautta. Painike näkyy " +
            "vain viime viikon tehdyissä ja lähipäivien tulevissa harjoituksissa.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      ModelChooser(model, configured, onModelChange)

      HorizontalDivider()

      Text(text = "Avaimet", style = MaterialTheme.typography.titleMedium)
      Text(
        text =
          "Tarvitset avaimen vain siltä palvelulta, jonka mallin valitsit. Avainta ei kokeilla " +
            "tallennettaessa, koska jokainen kutsu maksaa — ensimmäinen analyysi kertoo toimiiko se.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      AnalysisProvider.entries.forEach { provider ->
        ProviderKey(
          provider = provider,
          hasKey = provider in configured,
          isSelected = provider == model.provider,
          saveFailed = provider == saveFailure,
          onSave = { onSaveApiKey(provider, it) },
          onClear = { onClearApiKey(provider) },
        )
      }
    }
  }
}

/**
 * The model list, grouped by provider.
 *
 * A **fixed list, not a text field**: a mistyped model id is a `404` at tap time, on a phone,
 * indistinguishable from a broken key by anyone not reading the source. Radio buttons cannot produce
 * that. Each option carries what it costs, because that is what makes the choice a real one rather
 * than a guess between names.
 *
 * An option whose provider has no key is still selectable, and says so rather than being disabled —
 * choosing a model is how you find out which key you need, so refusing the choice would put the
 * answer behind the question.
 */
@Composable
private fun ModelChooser(
  selected: AnalysisModel,
  configured: Set<AnalysisProvider>,
  onChange: (AnalysisModel) -> Unit,
) {
  Text(text = "Malli", style = MaterialTheme.typography.titleMedium)
  AnalysisModel.byProvider().forEach { (provider, models) ->
    Text(
      text = provider.label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 8.dp),
    )
    models.forEach { option ->
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
            text =
              if (provider in configured) option.detail
              else "${option.detail} · avain puuttuu",
            style = MaterialTheme.typography.bodySmall,
            color =
              if (provider in configured) MaterialTheme.colorScheme.onSurfaceVariant
              else MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

/**
 * One provider's key field.
 *
 * Collapsed to a single line once a key is stored, because the ordinary state of this section is
 * "set up, nothing to do" and three expanded forms would bury the model list above them. The
 * provider whose model is currently selected is marked, so it is obvious which of the three the next
 * tap will actually spend.
 */
@Composable
private fun ProviderKey(
  provider: AnalysisProvider,
  hasKey: Boolean,
  isSelected: Boolean,
  saveFailed: Boolean,
  onSave: (String) -> Unit,
  onClear: () -> Unit,
) {
  var apiKey by remember(provider) { mutableStateOf("") }

  /**
   * The pasted key is wiped once it has been stored, and **only** once it has been stored.
   *
   * There used to be an unconditional `apiKey = ""` in the save button, which was removed when the
   * field moved from `rememberSaveable` to `remember` — the saved-instance-state risk it guarded
   * against was genuinely gone. The other half of its job was not: with `remember` the plaintext
   * key simply stays in composition for as long as this card is on screen. Clearing it on success
   * restores that, and keying it to [hasKey] rather than to the tap is what makes it *better* than
   * the line it replaces — a failed write now leaves the key in the field, so retrying does not
   * mean pasting a sixty-character secret a second time.
   */
  LaunchedEffect(hasKey) {
    if (hasKey) apiKey = ""
  }

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      text = if (isSelected) "${provider.label} — käytössä" else provider.label,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
    )

    if (hasKey) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "Avain tallennettu.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
          onClick = onClear,
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text("Poista")
        }
      }
    } else {
      if (saveFailed) {
        Text(
          text =
            "Avainta ei voitu tallentaa turvallisesti. Tarkista laitteen suojaus ja yritä uudelleen.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Text(
        text = keyHint(provider),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("${provider.label}-avain") },
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
      )
      Button(
        onClick = {
          onSave(apiKey)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = apiKey.isNotBlank(),
      ) {
        Text("Tallenna avain")
      }
    }
  }
}

/** Where each key comes from. Named rather than linked — these open in a browser, on the phone. */
private fun keyHint(provider: AnalysisProvider): String =
  when (provider) {
    AnalysisProvider.ANTHROPIC -> "Luo avain: console.anthropic.com"
    AnalysisProvider.OPENAI -> "Luo avain: platform.openai.com (ei sisälly ChatGPT Plus -tilaukseen)"
    AnalysisProvider.GEMINI -> "Luo avain: aistudio.google.com — käytä maksullista tasoa"
  }
