package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.AdvisorOperation
import fi.merilainen.treenivalmentaja.domain.AiPlanProposalState

@Composable
fun AiPlanAdvisorSection(
  state: AiPlanProposalState?,
  onRequest: (String?) -> Unit,
  onApply: () -> Unit,
  onDismiss: () -> Unit,
) {
  when (state) {
    null ->
      OutlinedButton(onClick = { onRequest(null) }, modifier = Modifier.fillMaxWidth()) {
        Text("Pyydä AI:lta muutosehdotus")
      }
    AiPlanProposalState.Loading ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator()
        Text("Muodostetaan tarkistettavaa ehdotusta…")
      }
    is AiPlanProposalState.NeedsClarification -> {
      var answer by rememberSaveable(state.question) { mutableStateOf("") }
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("AI tarvitsee tarkennuksen", fontWeight = FontWeight.Bold)
          Text(state.question)
          OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text("Vastauksesi") },
            modifier = Modifier.fillMaxWidth(),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRequest(answer) }, enabled = answer.isNotBlank()) { Text("Jatka") }
            TextButton(onClick = onDismiss) { Text("Peruuta") }
          }
        }
      }
    }
    is AiPlanProposalState.Ready ->
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        var promptVisible by rememberSaveable(state.prompt) { mutableStateOf(false) }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Tarkista ennen hyväksymistä", fontWeight = FontWeight.Bold)
          Text(state.proposal.summary)
          state.proposal.operations.forEach { operation ->
            Text("• ${operation.label()}", style = MaterialTheme.typography.bodyMedium)
          }
          Text(
            "Kalenteriin ei ole vielä kirjoitettu mitään.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(onClick = { promptVisible = !promptVisible }) {
            Text(if (promptVisible) "Piilota AI:lle lähetetty pyyntö" else "Näytä AI:lle lähetetty pyyntö")
          }
          if (promptVisible) {
            Text(
              state.prompt,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Hyväksy muutokset") }
          TextButton(onClick = onDismiss) { Text("Hylkää ehdotus") }
        }
      }
    is AiPlanProposalState.Applying ->
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator()
        Text("Toteutetaan hyväksyttyjä muutoksia…")
      }
    is AiPlanProposalState.Applied ->
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(state.summary)
          TextButton(onClick = onDismiss) { Text("Sulje") }
        }
      }
    is AiPlanProposalState.Failed ->
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canRetry) TextButton(onClick = { onRequest(null) }) { Text("Yritä uudelleen") }
            TextButton(onClick = onDismiss) { Text("Sulje") }
          }
        }
      }
  }
}

private fun AdvisorOperation.label(): String =
  when (this) {
    is AdvisorOperation.Move ->
      "Siirrä $sessionId päivälle $newDate${newTime?.let { " klo $it" }.orEmpty()}"
    is AdvisorOperation.Lighten -> "Ota harjoituksesta $sessionId kevyempi versio"
  }
