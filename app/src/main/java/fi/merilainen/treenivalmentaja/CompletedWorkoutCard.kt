package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import fi.merilainen.treenivalmentaja.domain.*
import java.util.Locale

@Composable
fun CompletedWorkoutCard(
  workout: Workout,
  run: CompletedRunMetrics?,
  completed: CompletedSessionMetrics?,
  analysis: AiAnalysisState?,
  configured: Boolean,
  onRequest: () -> Unit,
  onDismiss: () -> Unit,
  onExerciseClick: ((Exercise) -> Unit)?,
  proposal: AiPlanProposalState?,
  onRequestProposal: (String?) -> Unit,
  onApplyProposal: () -> Unit,
  onDismissProposal: () -> Unit,
  autoCompleted: Boolean = false,
  onUndoAutoCompletion: () -> Unit = {},
  onComplete: () -> Unit = {},
) {
  var details by rememberSaveable(workout.id) { mutableStateOf(false) }
  var readAnalysis by rememberSaveable(workout.id) { mutableStateOf(false) }
  Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(workout.type.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(if (workout.status == SessionStatus.COMPLETED) "Suoritettu" else workout.status.title,
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
      }
      if (autoCompleted) TextButton(onClick = onUndoAutoCompletion) { Text("Merkitty automaattisesti · Peru merkintä") }
      if (workout.status.canTransitionTo(SessionStatus.COMPLETED)) OutlinedButton(onClick = onComplete) { Text("Merkitse suoritetuksi") }
      if (run == null) completed?.let { CompletedMetricsRow(it) }
      run?.let {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Metric(it.distanceKm?.let { km -> String.format(Locale.forLanguageTag("fi"), "%.2f km", km) } ?: "—", "Matka", Modifier.weight(1f))
          Metric(it.primaryDurationSec.formatDuration(), if (it.activeDurationSec != null) "Aktiivinen aika" else "Liikkeessä", Modifier.weight(1f))
          Metric(it.avgHeartRate?.let { hr -> "$hr" } ?: "—", "Keskisyke /min", Modifier.weight(1f))
        }
        Text("Kello · Intervals.icu", style = MaterialTheme.typography.labelSmall)
        RunLapsSection(it.laps)
      }
      val scheme = MaterialTheme.colorScheme
      if (run != null) RunProfileCard(workout.runSteps, title = "Juoksun vaiheet", recording = run.trace,
        completed = true, showPrescription = false, laps = run.laps)
      Card(border = BorderStroke(1.dp, scheme.primary.copy(alpha = .35f)),
        colors = CardDefaults.cardColors(containerColor = if (scheme.surface.luminance() < .3f) scheme.primaryContainer else scheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(if (analysis is AiAnalysisState.Loaded) "AI-analyysi valmis" else "AI-analyysi", style = MaterialTheme.typography.titleMedium)
          if (analysis is AiAnalysisState.Loaded && !readAnalysis) {
            Text(analysis.text.take(200), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Button(onClick = { readAnalysis = true }) { Text("Lue analyysi") }
          } else {
            AiAnalysisSection(AiAnalysisKind.COMPLETED, analysis, configured, onRequest, onDismiss,
              proposal, onRequestProposal, onApplyProposal, onDismissProposal)
            if (!configured && analysis == null) Text("Valitse AI-palvelu ja tallenna avain asetuksissa.")
            if (analysis is AiAnalysisState.Loaded) TextButton(onClick = { readAnalysis = false }) { Text("Tiivistä analyysi") }
          }
        }
      }
      if (workout.appliedLighterVariant) Text("Kevennetty versio käytössä.", style = MaterialTheme.typography.bodySmall)
      if (workout.runSteps.isNotEmpty() && run == null) RunProfileCard(workout.runSteps, title = "Mitä oli suunniteltu")
      else Text("Mitä oli suunniteltu", style = MaterialTheme.typography.titleMedium)
      if (run != null && workout.runSteps.isNotEmpty()) Text(workout.runSteps.compactPrescription().joinToString("\n"))
      TextButton(onClick = { details = !details }) { Text(if (details) "Sulje ohjeet ja mittarit" else "Kaikki ohjeet ja mittarit") }
      if (details) {
        completed?.let { CompletedMetricsRow(it) }
        run?.let { RunMetricsRow(it) }
        if (workout.runSteps.isNotEmpty()) {
          Text(workout.description)
          workout.runSteps.forEachIndexed { index, step -> Text("${index + 1}. ${step.summary()}") }
        } else WorkoutDetails(workout, onExerciseClick = onExerciseClick)
      }
    }
  }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier) {
  Column(modifier) {
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(label, style = MaterialTheme.typography.labelSmall)
  }
}
