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
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.domain.OuraDiagnostics

/**
 * The Oura connection, as a function of its state.
 *
 * Four states worth telling apart, because the answer to "what do I do about this" differs in each:
 * a build with no credentials cannot be fixed from this screen at all, a disconnected one is one
 * tap away, a login in progress is waiting on a browser, and a connected one offers only the way
 * out. The failure message is carried in the state and shown as written — it is composed where the
 * failure happened, which is the only place that knows whether a token was rejected or a `state`
 * did not match.
 */
@Composable
fun OuraCard(
  state: OuraConnectionState,
  onConnect: () -> Unit = {},
  onDisconnect: () -> Unit = {},
  onDismissFailure: () -> Unit = {},
  onSaveCredentials: (String, String) -> Unit = { _, _ -> },
  onForgetCredentials: () -> Unit = {},
  diagnostics: OuraDiagnostics? = null,
  diagnosing: Boolean = false,
  onRunDiagnostics: () -> Unit = {},
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
        text = "Oura",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )

      when (state) {
        OuraConnectionState.NotConfigured -> CredentialsNeeded(onSaveCredentials)
        OuraConnectionState.Disconnected -> Disconnected(onConnect, onForgetCredentials)
        OuraConnectionState.Connecting -> Connecting()
        OuraConnectionState.Connected -> {
          Connected(onDisconnect)
          Diagnostics(diagnostics = diagnostics, running = diagnosing, onRun = onRunDiagnostics)
        }
        is OuraConnectionState.Failed -> Failed(state.message, onConnect, onDismissFailure)
      }
    }
  }
}

/**
 * The one-off setup, done on the phone.
 *
 * Oura withdrew personal access tokens, so an application registered in their developer portal is
 * the only way in — and its client id and secret are typed here rather than compiled into the
 * build. That is what keeps the whole feature reachable from a phone that only ever receives an
 * APK. See ADR-009.
 *
 * The secret is masked as it is typed, and the button stays disabled until both fields have
 * something in them, because the only check possible here is that they are not empty: whether they
 * are the *right* credentials is a question only Oura can answer.
 */
@Composable
private fun CredentialsNeeded(onSave: (String, String) -> Unit) {
  var clientId by rememberSaveable { mutableStateOf("") }
  var clientSecret by rememberSaveable { mutableStateOf("") }

  Text(
    text = "Yhdistäminen vaatii Oura-sovelluksen tunnukset. Ne annetaan kerran, tässä.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Text(
    text =
      "1. Kirjaudu Ouran kehittäjäportaaliin (developer.ouraring.com) — se toimii puhelimen " +
        "selaimessa.\n" +
        "2. Luo sovellus ja aseta sen Redirect URI:ksi täsmälleen:\n" +
        "treenivalmentaja://oauth2callback\n" +
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
      "Yhdistä Oura, niin sovellus näkee palautumisen, unen ja tehdyt treenit. " +
        "Kirjautuminen avautuu selaimeen.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  HorizontalDivider()
  Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Yhdistä Oura") }
  // The way back for a mistyped client id — otherwise the fields would be unreachable once saved.
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

/**
 * What Oura returned, as counts and one line per workout.
 *
 * Here rather than in a hidden developer menu because the person who needs it is the only person
 * who has this app. It exists because "Oura shows the session and Treenivalmentaja does not" had no
 * answer from the outside: the phone makes the requests, so the phone reports. Nobody has to send
 * their Oura credentials anywhere to find out what the API said.
 */
@Composable
private fun Diagnostics(
  diagnostics: OuraDiagnostics?,
  running: Boolean,
  onRun: () -> Unit,
) {
  HorizontalDivider()
  Text(
    text = "Mitä Oura palauttaa",
    style = MaterialTheme.typography.titleMedium,
  )
  Text(
    text =
      "Hakee saman kuin normaali synkronointi, mutta ei tallenna mitään. Kertoo montako " +
        "riviä kustakin kokoelmasta tuli.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  OutlinedButton(onClick = onRun, modifier = Modifier.fillMaxWidth(), enabled = !running) {
    Text(if (running) "Haetaan…" else "Tarkista Oura-data")
  }
  diagnostics?.let { result ->
    Text(
      text = "Aikaväli ${result.fromDate} – ${result.toDate}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // "päivää" and "näytettä" spelled out: these are row counts, and read as scores without them —
    // "Palautuminen 5" looks like a readiness of 5 rather than five days of it.
    Text(
      text =
        "Palautuminen ${result.readinessDays} pv · Uni ${result.sleepDays} pv · " +
          "Aktiivisuus ${result.activityDays} pv · Syke ${result.heartRateSamples} näytettä",
      style = MaterialTheme.typography.bodyMedium,
    )
    Text(
      text = "Treenit: ${result.workoutCount}",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold,
    )
    if (result.workouts.isEmpty()) {
      Text(
        // The finding this whole screen was built to make legible.
        text = "Oura ei palauttanut yhtään treeniä tältä aikaväliltä.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
      )
    } else {
      result.workouts.forEach {
        Text(text = it, style = MaterialTheme.typography.bodySmall)
      }
    }
    result.failures.forEach {
      Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
private fun Connected(onDisconnect: () -> Unit) {
  Text(
    text = "Oura on yhdistetty.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Text(
    // Said here rather than discovered later: the app cannot revoke its own access, because the
    // Oura specification documents no endpoint for it.
    text =
      "Katkaiseminen poistaa tunnukset ja Ourasta haetut tiedot tästä laitteesta. " +
        "Harjoitussuunnitelma säilyy. Sovelluksen käyttöoikeuden voi perua myös Ouran " +
        "omista asetuksista.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  HorizontalDivider()
  OutlinedButton(
    onClick = onDisconnect,
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
  ) {
    Text("Katkaise Oura-yhteys")
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
