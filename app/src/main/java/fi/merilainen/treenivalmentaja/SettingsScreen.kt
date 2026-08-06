package fi.merilainen.treenivalmentaja
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(viewModel: WorkoutViewModel) {
    val settings by viewModel.notificationSettings.collectAsState()
    val importFeedback by viewModel.importFeedback.collectAsState()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Reading the document is I/O; parsing and the Room write happen in the repository.
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            viewModel.importPlanJson(text)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Asetukset",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Ilmoitukset",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Aseta mihin aikaan haluat ilmoituksen treenistä, jos et ole sitä vielä tehnyt. Eri lajeille voi asettaa eri ajat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                NotificationTimeSetting(
                    title = WorkoutType.RUNNING.title,
                    time = settings.runningTime,
                    onTimeChange = { viewModel.updateNotificationTime(WorkoutType.RUNNING, it) }
                )

                NotificationTimeSetting(
                    title = WorkoutType.STRENGTH.title,
                    time = settings.strengthTime,
                    onTimeChange = { viewModel.updateNotificationTime(WorkoutType.STRENGTH, it) }
                )

                NotificationTimeSetting(
                    title = WorkoutType.SKIING.title,
                    time = settings.skiingTime,
                    onTimeChange = { viewModel.updateNotificationTime(WorkoutType.SKIING, it) }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Harjoitussuunnitelma",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Tuo suunnitelma JSON-muodossa (Treenivalmentaja Training Plan Schema v1). " +
                        "Tiedosto tarkistetaan ennen tallennusta: virheellistä suunnitelmaa ei viedä " +
                        "tietokantaan lainkaan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                Button(
                    onClick = {
                        filePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tuo tiedostosta")
                }

                OutlinedButton(
                    onClick = { viewModel.importPlanJson(clipboard.getText()?.text) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tuo leikepöydältä")
                }

                OutlinedButton(
                    onClick = { viewModel.resetSampleData() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Palauta esimerkkidata")
                }
            }
        }
    }

    importFeedback?.let { feedback ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportFeedback,
            confirmButton = {
                TextButton(onClick = viewModel::dismissImportFeedback) { Text("Sulje") }
            },
            title = { Text(feedback.title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = feedback.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (feedback.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        )
    }
}

@Composable
fun NotificationTimeSetting(title: String, time: String, onTimeChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = time,
            onValueChange = { onTimeChange(it) },
            modifier = Modifier.width(120.dp),
            singleLine = true,
            label = { Text("Klo") }
        )
    }
}
