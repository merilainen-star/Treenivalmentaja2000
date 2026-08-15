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
import fi.merilainen.treenivalmentaja.data.strava.StravaConnectionState

/**
 * The Strava connection, as a function of its state — the same five states as [OuraCard], told
 * apart for the same reason: the answer to "what do I do about this" differs in each.
 */
@Composable
fun StravaCard(
  state: StravaConnectionState,
  onConnect: () -> Unit = {},
  onDisconnect: () -> Unit = {},
  onDismissFailure: () -> Unit = {},
  onSaveCredentials: (String, String) -> Unit = { _, _ -> },
  onForgetCredentials: () -> Unit = {},
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
        text = "Strava",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )

      when (state) {
        StravaConnectionState.NotConfigured -> CredentialsNeeded(onSaveCredentials)
        StravaConnectionState.Disconnected -> Disconnected(onConnect, onForgetCredentials)
        StravaConnectionState.Connecting -> Connecting()
        StravaConnectionState.Connected -> Connected(onDisconnect)
        is StravaConnectionState.Failed -> Failed(state.message, onConnect, onDismissFailure)
      }
    }
  }
}

/**
 * The one-off setup, done on the phone — the ADR-009 pattern: the API application's credentials
 * are typed here rather than compiled into the build. See `docs/STRAVA_SETUP.md` for the steps on
 * Strava's side.
 */
@Composable
private fun CredentialsNeeded(onSave: (String, String) -> Unit) {
  var clientId by rememberSaveable { mutableStateOf("") }
  var clientSecret by rememberSaveable { mutableStateOf("") }

  Text(
    text = "Yhdistäminen vaatii Strava API -sovelluksen tunnukset. Ne annetaan kerran, tässä.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Text(
    text =
      "1. Kirjaudu Stravaan ja avaa www.strava.com/settings/api — se toimii puhelimen " +
        "selaimessa.\n" +
        "2. Luo sovellus ja aseta sen Authorization Callback Domain -kenttään täsmälleen:\n" +
        "localhost\n" +
        "3. Kopioi Client ID ja Client Secret alle.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  HorizontalDivider()

  OutlinedTextField(
    value = clientId,
    onValueChange = { clientId = it },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    label = { Text("Client ID") },
  )
  OutlinedTextField(
    value = clientSecret,
    onValueChange = { clientSecret = it },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    label = { Text("Client Secret") },
    visualTransformation = PasswordVisualTransformation(),
  )
  Button(
    onClick = { onSave(clientId, clientSecret) },
    modifier = Modifier.fillMaxWidth(),
    enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
  ) {
    Text("Tallenna tunnukset")
  }
}

@Composable
private fun Disconnected(onConnect: () -> Unit, onForgetCredentials: () -> Unit) {
  Text(
    text =
      "Yhdistä Strava, niin sovellus näkee juoksujen vauhdin, matkan ja sykkeen. " +
        "Kirjautuminen avautuu selaimeen.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  HorizontalDivider()
  Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Yhdistä Strava") }
  TextButton(onClick = onForgetCredentials, modifier = Modifier.fillMaxWidth()) {
    Text("Vaihda tunnukset")
  }
}

@Composable
private fun Connecting() {
  Text(
    text = "Odotetaan kirjautumista selaimessa…",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  CircularProgressIndicator(modifier = Modifier.size(24.dp))
}

@Composable
private fun Connected(onDisconnect: () -> Unit) {
  Text(
    text = "Strava on yhdistetty.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Text(
    text =
      "Katkaiseminen poistaa tunnukset ja Stravasta haetut tiedot tästä laitteesta. " +
        "Harjoitussuunnitelma säilyy. Sovelluksen käyttöoikeuden voi perua myös Stravan " +
        "omista asetuksista (My Apps).",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  HorizontalDivider()
  OutlinedButton(
    onClick = onDisconnect,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
  ) {
    Text("Katkaise Strava-yhteys")
  }
}

@Composable
private fun Failed(message: String, onConnect: () -> Unit, onDismiss: () -> Unit) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "Yhdistäminen epäonnistui",
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
  Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Yritä uudelleen") }
  TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Sulje") }
}
