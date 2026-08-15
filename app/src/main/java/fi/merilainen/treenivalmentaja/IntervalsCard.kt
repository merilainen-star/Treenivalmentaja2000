package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsConnectionState

/**
 * The intervals.icu connection, as a function of its state.
 *
 * Much smaller than the Oura card it sits beside, and that is the whole argument for a personal
 * API key here: there is no browser round trip to narrate, no "connecting" limbo, and no
 * disconnect that might or might not have reached a server. There is a key, or there is not.
 *
 * The key itself is never redisplayed once saved — only whether one is stored. A field that echoes
 * a stored secret back is a way for it to end up on a screen, in a screenshot, or over someone's
 * shoulder, and there is no operation here that needs the user to read it again.
 */
@Composable
fun IntervalsCard(
  state: IntervalsConnectionState,
  syncFailure: String? = null,
  onSaveApiKey: (String) -> Unit = {},
  onTestApiKey: () -> Unit = {},
  onClearApiKey: () -> Unit = {},
  onDismissFailure: () -> Unit = {},
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
        text = "Intervals.icu",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )

      when (state) {
        IntervalsConnectionState.NotConfigured -> KeyNeeded(onSaveApiKey)
        IntervalsConnectionState.Configured ->
          Stored(tested = null, onTest = onTestApiKey, onClear = onClearApiKey)
        IntervalsConnectionState.Testing -> Testing()
        is IntervalsConnectionState.Verified ->
          Stored(tested = state.activities, onTest = onTestApiKey, onClear = onClearApiKey)
        is IntervalsConnectionState.Failed ->
          Failed(state.message, onTestApiKey, onClearApiKey, onDismissFailure)
      }

      // A failed sync is a footnote rather than a dialog: whatever is already stored is still on
      // screen, and it stays true whether or not the last fetch reached intervals.icu.
      syncFailure?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/**
 * The one-off setup, done on the phone.
 *
 * The same shape as the Oura credentials field and for the same reason — the key is typed in
 * rather than compiled into the build
 * ([ADR-009](../../../../../../docs/DECISIONS.md)) — so the whole setup happens on a phone that
 * only ever receives an APK. See `docs/INTERVALS_SETUP.md` for the steps on intervals.icu's side.
 */
@Composable
private fun KeyNeeded(onSave: (String) -> Unit) {
  var apiKey by rememberSaveable { mutableStateOf("") }

  Text(
    text =
      "Suunto-kellon treenit luetaan Intervals.icu:sta. Se vaatii henkilökohtaisen API-avaimen, " +
        "joka annetaan kerran, tässä.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Text(
    text =
      "1. Kirjaudu intervals.icu-palveluun ja avaa Settings — se toimii puhelimen selaimessa.\n" +
        "2. Vieritä kohtaan Developer Settings.\n" +
        "3. Kopioi API Key alle.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  HorizontalDivider()

  OutlinedTextField(
    value = apiKey,
    onValueChange = { apiKey = it },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    label = { Text("API Key") },
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

/**
 * A key is stored.
 *
 * @param tested how many activities the last successful test found, or `null` when the key has not
 *   been tried since it was saved. **Zero is not a failure** — it means the key authenticated and
 *   the account had nothing in the window, and saying otherwise would send someone hunting for a
 *   broken key that is fine.
 */
@Composable
private fun Stored(tested: Int?, onTest: () -> Unit, onClear: () -> Unit) {
  Text(
    text = "API-avain on tallennettu.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  when (tested) {
    null ->
      Text(
        text = "Avainta ei ole vielä kokeiltu.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    0 ->
      Text(
        text = "Yhteys toimii. Intervals.icu:ssa ei ollut yhtään harjoitusta viime vuodelta.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    else ->
      Text(
        text = "Yhteys toimii.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
  Text(
    text =
      "Avaimen poistaminen poistaa sen ja Intervals.icu:sta haetut harjoitukset tästä " +
        "laitteesta. Harjoitussuunnitelma säilyy.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  HorizontalDivider()
  OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Testaa yhteys") }
  TextButton(
    onClick = onClear,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
  ) {
    Text("Poista avain")
  }
}

@Composable
private fun Testing() {
  Text(
    text = "Kokeillaan yhteyttä…",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  CircularProgressIndicator(modifier = Modifier.size(24.dp))
}

@Composable
private fun Failed(
  message: String,
  onTest: () -> Unit,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "Yhteys ei toiminut",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
    }
  }
  Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("Yritä uudelleen") }
  OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Vaihda avain") }
  TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Sulje") }
}
