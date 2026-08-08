package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Asks where in the calendar an imported plan should land.
 *
 * A plan file carries dates, but they are the coach's calendar and need not be yours. Both
 * readings are legitimate — a plan written for a specific season belongs on its own dates, while
 * a generic eight-week programme should start when you start it — and the app cannot tell which
 * one this file is. So it asks, once, at the moment the question arises.
 *
 * The file's own dates are the default: they are what the document actually says, and choosing
 * them changes nothing about it.
 */
@Composable
fun ImportStartDialog(onDismiss: () -> Unit, onConfirm: (startToday: Boolean) -> Unit) {
    var startToday by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Milloin ohjelma alkaa?") },
        text = {
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StartOption(
                    selected = !startToday,
                    onSelect = { startToday = false },
                    title = "Tiedoston päivämäärillä",
                    detail = "Treenit menevät niille päiville jotka tiedostossa on. " +
                        "Jos ohjelma on jo käynnissä, se jatkuu oikeasta kohdasta."
                )
                StartOption(
                    selected = startToday,
                    onSelect = { startToday = true },
                    title = "Alkaa tästä päivästä",
                    detail = "Ohjelman ensimmäinen päivä on tänään ja loput siirtyvät saman " +
                        "verran. Treenien järjestys ja lepopäivät säilyvät."
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(startToday) }) { Text("Tuo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } }
    )
}

@Composable
private fun StartOption(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    detail: String,
) {
    // The whole row is the target, and the RadioButton itself is not separately clickable, so
    // TalkBack announces one control rather than two.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
