package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.merilainen.treenivalmentaja.data.repository.PlanPreviewResult
import fi.merilainen.treenivalmentaja.data.repository.RunExportResult
import fi.merilainen.treenivalmentaja.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun PlanDocumentDialog(raw: String, viewModel: WorkoutViewModel, onImport: (String, Boolean) -> Unit) {
  var preview by remember(raw) { mutableStateOf<PlanPreviewResult?>(null) }
  var trial by remember(raw) { mutableStateOf(false) }
  var importing by remember(raw) { mutableStateOf(false) }
  LaunchedEffect(raw) { preview = viewModel.previewTrial(raw) }
  val ready = preview as? PlanPreviewResult.Valid
  when {
    importing -> ImportStartDialog(
      onDismiss = { importing = false },
      onConfirm = { today -> viewModel.closePlanDocument(); onImport(raw, today) },
    )
    trial && ready != null -> PlanTrialDialog(ready, viewModel.trialDate(), viewModel::closePlanDocument, viewModel::exportTrial)
    else -> AlertDialog(
      onDismissRequest = viewModel::closePlanDocument,
      title = { Text(ready?.name ?: "Avaa ohjelma") },
      text = {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
          when (val result = preview) {
            null -> Text("Tarkistetaan ohjelmaa…")
            is PlanPreviewResult.Invalid -> Text(result.errors.joinToString("\n"))
            is PlanPreviewResult.Valid -> Text("${result.sessions.size} harjoitusta. Kokeilu säilyttää nykyisen ohjelman ja sen kirjaukset. Ohjelmaksi tuominen voi korvata nykyisen ohjelman erillisen vahvistuksen jälkeen.")
          }
        }
      },
      confirmButton = { TextButton(onClick = { trial = true }, enabled = ready != null) { Text("Kokeile") } },
      dismissButton = {
        Column {
          TextButton(onClick = { importing = true }, enabled = ready != null) { Text("Tuo ohjelmaksi") }
          TextButton(onClick = viewModel::closePlanDocument) { Text("Sulje") }
        }
      },
    )
  }
}

@Composable
fun PlanTrialDialog(
  plan: PlanPreviewResult.Valid,
  today: LocalDate,
  onClose: () -> Unit,
  onExport: suspend (TrainingSession, LocalDate) -> RunExportResult,
) {
  var selected by remember(plan) { mutableStateOf<TrainingSession?>(null) }
  var lighter by remember(selected) { mutableStateOf(false) }
  val session = selected?.let { original ->
    if (!lighter) original else original.copy(
      runSteps = original.lighterAlternative?.runSteps,
      exercises = original.lighterAlternative?.exercises,
      durationMin = original.lighterAlternative?.durationMin,
      description = original.lighterAlternative?.description,
      rounds = original.lighterAlternative?.rounds,
      roundsMin = original.lighterAlternative?.roundsMin,
      roundRestSec = original.lighterAlternative?.roundRestSec,
    )
  }
  val steps = remember(session) { session?.let(::trialSteps).orEmpty() }
  var progress by remember(session) { mutableStateOf(TrialProgress()) }
  var speed by remember { mutableIntStateOf(1) }
  var confirming by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var message by remember(session) { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(progress.running, session, speed) {
    while (progress.running) { delay(1000); progress = progress.tick(steps, speed) }
  }
  Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.safeDrawingPadding().padding(20.dp)) {
        Text("Ohjelman kokeilu", style = MaterialTheme.typography.headlineSmall)
        Text("Kokeilun vaiheita ja suorituksia ei tallenneta. Kellovienti luo oikean testiharjoituksen Intervals.icu:hun.")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (session == null) {
            Text(plan.name, style = MaterialTheme.typography.titleMedium)
            plan.sessions.sortedBy { it.scheduledDate }.forEach { item ->
              OutlinedButton(onClick = { selected = item }) {
                Text("${item.scheduledDate} · ${item.type.title}\n${item.description.orEmpty().lineSequence().firstOrNull().orEmpty()}")
              }
            }
          } else {
            Text("${session.scheduledDate} · ${session.type.title}")
            if (selected?.lighterAlternative != null) TextButton(onClick = { lighter = !lighter }, enabled = !busy) {
              Text(if (lighter) "Vaihda pääversioon" else "Kokeile kevyttä vaihtoehtoa")
            }
            val current = steps.getOrNull(progress.index)
            if (current == null) Text("Kokeilu valmis – nykyinen ohjelma säilyi.") else {
              Text("Vaihe ${progress.index + 1}/${steps.size}", style = MaterialTheme.typography.titleMedium)
              Text(current.title, style = MaterialTheme.typography.headlineSmall)
              Text(current.detail)
              current.seconds?.let { total ->
                Text("Jäljellä ${total - progress.elapsed} s")
                Button(onClick = { progress = progress.copy(running = !progress.running) }) { Text(if (progress.running) "Tauko" else "Käynnistä ajastin") }
                TextButton(onClick = { speed = if (speed == 1) 30 else 1 }) { Text("Nopeus ${speed}×") }
              }
              current.meters?.let { Text("Matkavaihe $it m. Simuloi matkan täyttyminen Seuraava vaihe -painikkeella.") }
              Button(onClick = { progress = progress.next(steps) }) { Text("Seuraava vaihe") }
            }
            TextButton(onClick = { progress = TrialProgress() }) { Text("Aloita alusta") }
            if (session.type == WorkoutType.RUNNING && session.runSteps?.validRunSteps() == true) {
              OutlinedButton(onClick = { confirming = true; progress = progress.copy(running = false) }, enabled = !busy) { Text("Vie testijuoksu kelloon tänään") }
            }
            message?.let { Text(it) }
            TextButton(onClick = { selected = null }, enabled = !busy) { Text("Valitse toinen harjoitus") }
          }
        }
        TextButton(onClick = onClose, enabled = !busy) { Text("Sulje kokeilu") }
      }
    }
  }
  if (confirming && session != null) AlertDialog(
    onDismissRequest = { confirming = false },
    title = { Text("Vie testijuoksu $today?") },
    text = { Text("Luo erillisen TESTI-harjoituksen Intervals.icu:hun. Synkronoi sen jälkeen Suunto-sovellus ja kello. Oikeita suunniteltuja harjoituksia ei poisteta. Uusintavienti päivittää saman testin; poista se tarvittaessa Intervals.icu-kalenterista. Kellolla tallennettu suoritus voi synkronoitua oikeaan historiaasi.") },
    confirmButton = { TextButton(onClick = {
      confirming = false; busy = true
      scope.launch {
        try {
          message = when (val result = onExport(session, today)) {
            is RunExportResult.Success -> "Testi viety. Synkronoi Suunto-sovellus ja kello."
            is RunExportResult.Failure -> result.message
          }
        } finally { busy = false }
      }
    }) { Text("Vie testi") } },
    dismissButton = { TextButton(onClick = { confirming = false }) { Text("Peruuta") } },
  )
}
