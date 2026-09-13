package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.RunStep
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import fi.merilainen.treenivalmentaja.domain.validRunSteps

@Composable
fun WatchRunsCard(
  runs: List<TrainingSession>,
  connected: Boolean,
  busy: Boolean,
  message: String?,
  onSave: (String, List<RunStep>) -> Unit,
  onExport: () -> Unit,
) {
  var editing by remember { mutableStateOf<TrainingSession?>(null) }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Juoksut Suuntoon", style = MaterialTheme.typography.titleLarge)
      Text("Tänään ja seuraavat 6 päivää. Tarkista vaiheet ennen vientiä. Voimaharjoituksia ei viedä.")
      Text("Ota Intervals.icu:n Settings → Suunto -kohdassa Upload planned workouts käyttöön ja valitse Run. Synkronoi viennin jälkeen Suunto-sovellus ja kello.", style = MaterialTheme.typography.bodySmall)
      runs.forEach { run ->
        HorizontalDivider()
        Text("${run.scheduledDate} · ${run.scheduledTime ?: "Ei kellonaikaa"}")
        run.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (run.runSteps.isNullOrEmpty()) Text("Kellovaiheet puuttuvat. Määritä ne kuvauksen mukaan.")
        run.runSteps?.forEachIndexed { index, step -> Text("${index + 1}. ${step.summary()}") }
        OutlinedButton(onClick = { editing = run }, enabled = !busy) { Text("Muokkaa vaiheita") }
      }
      if (runs.isEmpty()) Text("Ei tulevia juoksuja tällä jaksolla.")
      Text("Vienti päivittää saman treenin ja poistaa tämän sovelluksen perutut tai siirretyt viennit jaksolta. Vie uudelleen, jos muutat ohjelmaa.", style = MaterialTheme.typography.bodySmall)
      Button(
        onClick = onExport,
        enabled = connected && !busy && runs.all { it.runSteps?.validRunSteps() == true },
      ) { Text(if (busy) "Viedään…" else "Vie juoksut Intervals.icu:hun") }
      if (!connected) Text("Tallenna ensin Intervals.icu-avain yllä.")
      message?.let { Text(it) }
    }
  }
  editing?.let { session ->
    RunStepsEditor(session, onDismiss = { editing = null }, onSave = {
      onSave(session.id, it)
      editing = null
    })
  }
}

private data class StepDraft(
  val name: String = "",
  val seconds: String = "",
  val metres: String = "",
  val pace: String = "",
) {
  fun step(): RunStep? {
    val duration = seconds.takeIf { it.isNotBlank() }?.toIntOrNull()
    val distance = metres.takeIf { it.isNotBlank() }?.toIntOrNull()
    val paceSeconds = pace.takeIf { it.isNotBlank() }?.let {
      val parts = it.split(':')
      if (parts.size != 2) return null
      val minutes = parts[0].toIntOrNull() ?: return null
      val remainder = parts[1].toIntOrNull()?.takeIf { n -> n in 0..59 } ?: return null
      if (minutes !in 0..30) return null
      minutes * 60 + remainder
    }
    if ((seconds.isNotBlank() && duration == null) || (metres.isNotBlank() && distance == null)) return null
    return RunStep(name.trim(), duration, distance, paceSeconds).takeIf { it.isValid() }
  }
}

/** Drafts stay local until the explicit save button is pressed. No prose-to-interval guessing. */
@Composable
fun RunStepsEditor(session: TrainingSession, onDismiss: () -> Unit, onSave: (List<RunStep>) -> Unit) {
  var drafts by remember(session.id) {
    mutableStateOf(session.runSteps?.map {
      StepDraft(it.name, it.durationSec?.toString().orEmpty(), it.distanceMeters?.toString().orEmpty(),
        it.paceSecPerKm?.let { p -> "${p / 60}:${(p % 60).toString().padStart(2, '0')}" }.orEmpty())
    } ?: listOf(StepDraft()))
  }
  val steps = drafts.mapNotNull { it.step() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Juoksun vaiheet · ${session.scheduledDate}") },
    text = {
      Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        session.description?.let { Text(it) }
        Text("Lisää lämmittely, vedot, palautukset ja loppuverryttely järjestyksessä. Anna kesto TAI matka. Toista vaiheet lisäämällä ne uudelleen.")
        drafts.forEachIndexed { index, draft ->
          fun update(value: StepDraft) { drafts = drafts.toMutableList().also { it[index] = value } }
          HorizontalDivider()
          Text("Vaihe ${index + 1}")
          OutlinedTextField(draft.name, { update(draft.copy(name = it)) }, label = { Text("Vaiheen nimi") }, singleLine = true)
          OutlinedTextField(draft.seconds, { update(draft.copy(seconds = it)) }, label = { Text("Kesto sekunteina") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
          OutlinedTextField(draft.metres, { update(draft.copy(metres = it)) }, label = { Text("Matka metreinä") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
          OutlinedTextField(draft.pace, { update(draft.copy(pace = it)) }, label = { Text("Vauhti min:ss/km (valinnainen)") }, singleLine = true)
          TextButton(onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } }) { Text("Poista vaihe ${index + 1}") }
        }
        OutlinedButton(onClick = { drafts = drafts + StepDraft() }, enabled = drafts.size < 100) { Text("Lisää vaihe") }
        if (steps.size != drafts.size || !steps.validRunSteps()) Text("Täytä jokaiselle vaiheelle nimi ja joko kesto (1–86400 s) tai matka (1–200000 m). Vauhti 1:00–30:00/km.")
      }
    },
    confirmButton = { TextButton(onClick = { onSave(steps) }, enabled = steps.size == drafts.size && steps.validRunSteps()) { Text("Tallenna vaiheet") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } },
  )
}
