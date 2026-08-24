package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AdvisorConstraintsCard(
  constraints: String,
  onSave: (String) -> Unit,
) {
  var draft by rememberSaveable(constraints) { mutableStateOf(constraints) }
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("AI-valmentajan rajat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(
        "Kirjaa pysyvät ehdot, joita muutosehdotus ei saa rikkoa. Esimerkiksi: pitkät lenkit vain viikonloppuna.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Pysyvät rajoitteet") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
      )
      Button(onClick = { onSave(draft) }, enabled = draft != constraints) { Text("Tallenna rajat") }
    }
  }
}
