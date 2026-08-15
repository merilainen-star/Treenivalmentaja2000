package fi.merilainen.treenivalmentaja
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.data.strava.StravaConnectionState
import fi.merilainen.treenivalmentaja.domain.OuraDiagnostics
import fi.merilainen.treenivalmentaja.domain.UpdateStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The stateful wrapper: the ViewModel, and the two things only a real screen can do — read a file
 * the picker handed back, and ask for the notification permission.
 *
 * Everything else is a parameter of [SettingsScreenContent], which is what lets a test render this
 * screen at all.
 */
@Composable
fun SettingsScreen(viewModel: WorkoutViewModel) {
    val settings by viewModel.notificationSettings.collectAsState()
    val importFeedback by viewModel.importFeedback.collectAsState()
    val pendingConfirmation by viewModel.pendingImport.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    val ouraState by viewModel.ouraState.collectAsState()
    val ouraAuthorizationUrl by viewModel.ouraAuthorizationUrl.collectAsState()
    val diagnostics by viewModel.ouraDiagnostics.collectAsState()
    val diagnosing by viewModel.ouraDiagnosing.collectAsState()
    val stravaState by viewModel.stravaState.collectAsState()
    val stravaAuthorizationUrl by viewModel.stravaAuthorizationUrl.collectAsState()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (!isGranted) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }
    }

    /** Plan text waiting for the user to say where in the calendar it should land. */
    var pendingImportJson by rememberSaveable { mutableStateOf<String?>(null) }

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
            // The start-date question is asked once the text is in hand, so a cancelled picker
            // never raises it.
            pendingImportJson = text
        }
    }

    pendingImportJson?.let { json ->
        ImportStartDialog(
            onDismiss = { pendingImportJson = null },
            onConfirm = { startToday ->
                pendingImportJson = null
                viewModel.importPlanJson(json, startToday = startToday)
            }
        )
    }

    // Asked only when the import would change or discard what is already stored, which is also
    // the only route by which that can happen.
    pendingConfirmation?.let { prompt ->
        ImportConfirmDialog(
            planName = prompt.planName,
            action = prompt.action,
            onConfirm = viewModel::confirmPendingImport,
            onDismiss = viewModel::cancelPendingImport,
        )
    }

    /**
     * Opening a browser is the third thing only a real screen can do, alongside the file picker
     * and the notification permission.
     *
     * An external browser rather than a WebView, deliberately: a WebView would let this app read
     * the Oura password as it is typed, which is exactly what the authorization-code flow exists to
     * avoid. It is also keyed on the URL, so a login started twice does not open two tabs.
     */
    LaunchedEffect(ouraAuthorizationUrl) {
        val url = ouraAuthorizationUrl ?: return@LaunchedEffect
        val opened =
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }.isSuccess
        if (opened) viewModel.ouraAuthorizationOpened()
        else viewModel.ouraAuthorizationFailedToOpen()
    }

    // Strava's login opens the same way and for the same reason: an external browser, never a
    // WebView that could read the password as it is typed.
    LaunchedEffect(stravaAuthorizationUrl) {
        val url = stravaAuthorizationUrl ?: return@LaunchedEffect
        val opened =
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }.isSuccess
        if (opened) viewModel.stravaAuthorizationOpened()
        else viewModel.stravaAuthorizationFailedToOpen()
    }

    SettingsScreenContent(
        settings = settings,
        updateStatus = updateStatus,
        ouraState = ouraState,
        onConnectOura = viewModel::connectOura,
        onDisconnectOura = viewModel::disconnectOura,
        onDismissOuraFailure = viewModel::dismissOuraFailure,
        onSaveOuraCredentials = viewModel::saveOuraCredentials,
        onForgetOuraCredentials = viewModel::forgetOuraCredentials,
        ouraDiagnostics = diagnostics,
        ouraDiagnosing = diagnosing,
        onRunOuraDiagnostics = viewModel::runOuraDiagnostics,
        stravaState = stravaState,
        onConnectStrava = viewModel::connectStrava,
        onDisconnectStrava = viewModel::disconnectStrava,
        onDismissStravaFailure = viewModel::dismissStravaFailure,
        onSaveStravaCredentials = viewModel::saveStravaCredentials,
        onForgetStravaCredentials = viewModel::forgetStravaCredentials,
        hasNotificationPermission = hasPermission,
        onRequestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onTimeChange = viewModel::updateNotificationTime,
        onImportFile = { filePicker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        onImportClipboard = {
            // An empty clipboard is reported straight away rather than after asking a question
            // about a plan that is not there.
            val text = clipboard.getText()?.text
            if (text.isNullOrBlank()) viewModel.importPlanJson(text) else pendingImportJson = text
        },
        onResetSampleData = viewModel::resetSampleData,
        onCheckUpdate = viewModel::checkForUpdate,
    )

    importFeedback?.let { feedback ->
        ImportFeedbackDialog(feedback, onDismiss = viewModel::dismissImportFeedback)
    }
}

/** Settings, as a function of what it is given. */
@Composable
fun SettingsScreenContent(
    settings: NotificationSettings,
    updateStatus: UpdateStatus = UpdateStatus.Idle,
    ouraState: OuraConnectionState = OuraConnectionState.NotConfigured,
    hasNotificationPermission: Boolean = true,
    onRequestNotificationPermission: () -> Unit = {},
    onConnectOura: () -> Unit = {},
    onDisconnectOura: () -> Unit = {},
    onDismissOuraFailure: () -> Unit = {},
    onSaveOuraCredentials: (String, String) -> Unit = { _, _ -> },
    onForgetOuraCredentials: () -> Unit = {},
    ouraDiagnostics: OuraDiagnostics? = null,
    ouraDiagnosing: Boolean = false,
    onRunOuraDiagnostics: () -> Unit = {},
    stravaState: StravaConnectionState = StravaConnectionState.NotConfigured,
    onConnectStrava: () -> Unit = {},
    onDisconnectStrava: () -> Unit = {},
    onDismissStravaFailure: () -> Unit = {},
    onSaveStravaCredentials: (String, String) -> Unit = { _, _ -> },
    onForgetStravaCredentials: () -> Unit = {},
    onTimeChange: (WorkoutType, String) -> Unit = { _, _ -> },
    onImportFile: () -> Unit = {},
    onImportClipboard: () -> Unit = {},
    onResetSampleData: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
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
                
                if (!hasNotificationPermission) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Ilmoituslupa puuttuu", 
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Treenimuistutukset eivät toimi ilman lupaa.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(
                                onClick = onRequestNotificationPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Anna lupa", color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }


                HorizontalDivider()

                NotificationTimeSetting(
                    title = WorkoutType.RUNNING.title,
                    time = settings.runningTime,
                    onTimeChange = { onTimeChange(WorkoutType.RUNNING, it) }
                )

                NotificationTimeSetting(
                    title = WorkoutType.STRENGTH.title,
                    time = settings.strengthTime,
                    onTimeChange = { onTimeChange(WorkoutType.STRENGTH, it) }
                )

                NotificationTimeSetting(
                    title = WorkoutType.SKIING.title,
                    time = settings.skiingTime,
                    onTimeChange = { onTimeChange(WorkoutType.SKIING, it) }
                )
            }
        }

        OuraCard(
            state = ouraState,
            onConnect = onConnectOura,
            onDisconnect = onDisconnectOura,
            onDismissFailure = onDismissOuraFailure,
            onSaveCredentials = onSaveOuraCredentials,
            onForgetCredentials = onForgetOuraCredentials,
            diagnostics = ouraDiagnostics,
            diagnosing = ouraDiagnosing,
            onRunDiagnostics = onRunOuraDiagnostics,
        )

        StravaCard(
            state = stravaState,
            onConnect = onConnectStrava,
            onDisconnect = onDisconnectStrava,
            onDismissFailure = onDismissStravaFailure,
            onSaveCredentials = onSaveStravaCredentials,
            onForgetCredentials = onForgetStravaCredentials,
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

                Button(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                    Text("Tuo tiedostosta")
                }

                OutlinedButton(onClick = onImportClipboard, modifier = Modifier.fillMaxWidth()) {
                    Text("Tuo leikepöydältä")
                }

                OutlinedButton(
                    onClick = onResetSampleData,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Palauta esimerkkidata")
                }

                HorizontalDivider()

                // Checked once per visit to Settings rather than on a timer: the whole point is
                // to answer the question you have while looking at this screen.
                LaunchedEffect(Unit) { onCheckUpdate() }
                UpdateCard(status = updateStatus, onCheck = onCheckUpdate)
            }
        }
    }

}

/**
 * What the last import did, or refused to do.
 *
 * Scrollable because a broken plan reports every problem at once, with a JSON path each — a
 * document with forty errors is a list of forty lines, and truncating it would hide the one that
 * mattered.
 */
@Composable
fun ImportFeedbackDialog(feedback: ImportFeedback, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Sulje") } },
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
