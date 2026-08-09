package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.data.importer.PendingImport

/**
 * Asks before an import touches a plan that is already stored.
 *
 * Two different questions wear the same button, so they are not asked the same way. Correcting
 * the programme you are running costs nothing and says so; replacing it costs the record of what
 * you have done, and says that instead — with the number of sessions it would take, because "your
 * history will be lost" means nothing until you know it is eleven sessions.
 *
 * Before this existed the app got both cases backwards: importing a plan with a different id
 * deleted everything without a word, while re-importing a corrected version of the same programme
 * was refused outright with an instruction to delete the old one first — for which there was no
 * button anywhere.
 */
@Composable
fun ImportConfirmDialog(
    planName: String,
    action: PendingImport,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title: String
    val detail: String
    val confirmLabel: String

    when (action) {
        is PendingImport.Update -> {
            title = "Päivitä suunnitelma?"
            confirmLabel = "Päivitä"
            detail = buildString {
                append("\"$planName\" on jo tuotu. ")
                append(
                    when {
                        action.changed > 0 && action.added > 0 ->
                            "${action.changed} harjoitusta päivittyy ja ${action.added} lisätään."
                        action.added > 0 -> "${action.added} uutta harjoitusta lisätään."
                        action.changed > 0 -> "${action.changed} harjoitusta päivittyy."
                        else -> "Harjoitukset pysyvät ennallaan; vain suunnitelman tiedot muuttuvat."
                    }
                )
                append(" Tehdyt merkinnät ja harjoitushistoria säilyvät.")
            }
        }
        is PendingImport.Replace -> {
            title = "Korvaa suunnitelma?"
            confirmLabel = "Korvaa"
            detail = buildString {
                append("Tämä poistaa suunnitelman \"${action.replacedPlanName}\". ")
                append(
                    if (action.recordedSessions > 0) {
                        "${action.recordedSessions} merkittyä harjoitusta ja niiden historia " +
                            "häviävät pysyvästi."
                    } else {
                        "Siihen ei ole merkitty yhtään tehtyä harjoitusta, joten mitään " +
                            "kirjattua ei häviä."
                    }
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = detail, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } }
    )
}
