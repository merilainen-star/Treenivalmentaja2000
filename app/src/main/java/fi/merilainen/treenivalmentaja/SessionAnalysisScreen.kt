package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.AiAnalysisKind

@Composable
fun SessionAnalysisScreen(sessionId: String, viewModel: WorkoutViewModel) {
  val analyses by viewModel.aiAnalyses.collectAsState()
  val workouts by viewModel.workouts.collectAsState()
  val configured by viewModel.analysisConfigured.collectAsState()
  val model by viewModel.analysisModel.collectAsState()
  val workout = workouts.firstOrNull { it.id == sessionId }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("Harjoituksen AI-analyysi", style = MaterialTheme.typography.headlineSmall)
    workout?.let { Text("${it.type.title} · ${it.time}") }
    if (analyses[sessionId] == null) Text("Tallennettua analyysiä ei ole saatavilla.")
    AiAnalysisSection(AiAnalysisKind.COMPLETED, analyses[sessionId], model.provider in configured,
      onRequest = { viewModel.requestAiAnalysis(sessionId) },
      onDismiss = { viewModel.dismissAiAnalysis(sessionId) })
    workout?.takeIf { it.runSteps.isNotEmpty() }?.let { RunProfileCard(it.runSteps) }
  }
}
