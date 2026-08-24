package fi.merilainen.treenivalmentaja

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutOutcome
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutStep
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
import fi.merilainen.treenivalmentaja.domain.SkippedMovement
import fi.merilainen.treenivalmentaja.domain.buildActiveWorkoutSteps
import fi.merilainen.treenivalmentaja.domain.completedMovements
import fi.merilainen.treenivalmentaja.domain.key
import fi.merilainen.treenivalmentaja.domain.skippedMovements
import kotlin.math.ceil
import kotlinx.coroutines.delay

@Composable
fun ActiveWorkoutScreen(
  sessionId: String,
  viewModel: WorkoutViewModel,
  onClose: () -> Unit,
) {
  val workouts by viewModel.workouts.collectAsState()
  val workout = workouts.firstOrNull { it.id == sessionId }
  val guideState by viewModel.guideState.collectAsState()

  LaunchedEffect(sessionId) { viewModel.startActiveWorkout(sessionId) }

  if (workout == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Harjoitusta ei löytynyt")
    }
    return
  }

  ActiveWorkoutContent(
    workout = workout,
    onClose = onClose,
    onExerciseClick = viewModel::openExerciseGuide,
    onComplete = { outcome ->
      viewModel.completeActiveWorkout(sessionId, outcome)
      onClose()
    },
  )

  guideState?.let { state ->
    ExerciseGuideSheet(
      state = state,
      onRetry = viewModel::retryExerciseGuide,
      onSelectSuggestion = viewModel::selectGuideSuggestion,
      onDismiss = viewModel::closeExerciseGuide,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutContent(
  workout: Workout,
  onClose: () -> Unit = {},
  onExerciseClick: (Exercise) -> Unit = {},
  onComplete: (ActiveWorkoutOutcome) -> Unit = {},
  initialOverviewVisible: Boolean = true,
  initialStepIndex: Int = 0,
  trackElapsed: Boolean = true,
) {
  val steps =
    remember(workout.exercises, workout.rounds, workout.roundRestSec) {
      buildActiveWorkoutSteps(workout.exercises, workout.rounds, workout.roundRestSec)
    }
  var overviewVisible by rememberSaveable(workout.id) { mutableStateOf(initialOverviewVisible) }
  var stepIndex by rememberSaveable(workout.id, steps.size) { mutableIntStateOf(initialStepIndex) }
  var skippedIds by rememberSaveable(workout.id, steps.size) { mutableStateOf(emptyList<String>()) }
  val startedAt = rememberSaveable(workout.id) { System.currentTimeMillis() }
  val elapsedSec = if (trackElapsed) rememberElapsedSeconds(startedAt) else 0

  val view = LocalView.current
  DisposableEffect(view) {
    val previous = view.keepScreenOn
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = previous }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Harjoitus käynnissä") },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Takaisin")
          }
        },
      )
    }
  ) { padding ->
    if (steps.isEmpty()) {
      Column(
        modifier = Modifier.padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Ohjattu tila tarvitsee suunnitelman rakenteisen exercises-listan.")
        OutlinedButton(onClick = onClose) { Text("Palaa päivän korttiin") }
      }
      return@Scaffold
    }

    if (overviewVisible) {
      ActiveWorkoutOverview(
        workout = workout,
        modifier = Modifier.padding(padding),
        onStart = { overviewVisible = false },
      )
      return@Scaffold
    }

    val safeIndex = stepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[safeIndex]
    val completed = steps.completedMovements(safeIndex, skippedIds)
    val total = workout.rounds * workout.exercises.size
    Column(
      modifier =
        Modifier
          .padding(padding)
          .fillMaxSize()
          .padding(horizontal = 20.dp, vertical = 12.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      WorkoutProgressHeader(
        completed = completed,
        total = total,
        round = activeRound(step),
        rounds = workout.rounds,
        elapsedSec = elapsedSec,
      )

      when (step) {
        is ActiveWorkoutStep.Prepare ->
          PrepareStepCard(
            step = step,
            onExerciseClick = onExerciseClick,
            onReady = { stepIndex = (safeIndex + 1).coerceAtMost(steps.lastIndex) },
          )
        is ActiveWorkoutStep.Perform ->
          PerformStepCard(
            step = step,
            upcoming = upcomingExercises(steps, safeIndex),
            onExerciseClick = onExerciseClick,
            onDone = { stepIndex = (safeIndex + 1).coerceAtMost(steps.lastIndex) },
            onSkip = {
              skippedIds = (skippedIds + step.key()).distinct()
              stepIndex = (safeIndex + 1).coerceAtMost(steps.lastIndex)
            },
          )
        is ActiveWorkoutStep.Rest ->
          CountdownStepCard(
            title = "Lepo",
            seconds = step.seconds,
            next = "Seuraavaksi ${step.nextExerciseName}",
            onFinished = { stepIndex = (safeIndex + 1).coerceAtMost(steps.lastIndex) },
          )
        is ActiveWorkoutStep.RoundBreak ->
          CountdownStepCard(
            title = "Kierrostauko",
            seconds = step.seconds,
            next = "Seuraavaksi kierros ${step.nextRound}",
            onFinished = { stepIndex = (safeIndex + 1).coerceAtMost(steps.lastIndex) },
          )
        ActiveWorkoutStep.Finish -> {
          val skipped = steps.skippedMovements(skippedIds)
          FinishWorkoutCard(
            total = total,
            skipped = skipped,
            onSave = { rpe, feel ->
              onComplete(
                ActiveWorkoutOutcome(
                  guided =
                    GuidedProgress(
                      done = (total - skipped.size).coerceAtLeast(0),
                      rounds = workout.rounds,
                      perRound = workout.exercises.size,
                    ),
                  skipped = skipped,
                  sessionRpe = rpe,
                  feel = feel,
                  durationSec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0),
                )
              )
            },
          )
        }
      }

      if (safeIndex > 0 && step !is ActiveWorkoutStep.Finish) {
        OutlinedButton(onClick = { stepIndex = safeIndex - 1 }, modifier = Modifier.fillMaxWidth()) {
          Text("Edellinen vaihe")
        }
      }
      Spacer(Modifier.height(12.dp))
    }
  }
}

@Composable
private fun ActiveWorkoutOverview(workout: Workout, modifier: Modifier, onStart: () -> Unit) {
  val equipment =
    workout.exercises.flatMap { it.equipment.orEmpty() }.distinct().ifEmpty {
      workout.exercises.mapNotNull { it.weightKg?.let { weight -> "$weight kg paino" } }.distinct()
    }
  Column(
    modifier = modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    Icon(Icons.Default.FitnessCenter, contentDescription = null, modifier = Modifier.size(48.dp))
    Text(workout.type.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("${workout.durationMin} min · ${workout.exercises.size} liikettä · ${workout.rounds} kierrosta")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Valmistele", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (equipment.isEmpty()) "Ei erikseen merkittyjä välineitä" else equipment.joinToString(" · "))
      }
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Aloita treeni") }
  }
}

@Composable
private fun WorkoutProgressHeader(
  completed: Int,
  total: Int,
  round: Int,
  rounds: Int,
  elapsedSec: Long,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Liikkeet $completed / $total")
        Text("Kierros $round / $rounds")
      }
      Text(
        text = "Kesto ${formatElapsed(elapsedSec)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      LinearProgressIndicator(
        progress = { if (total == 0) 0f else completed.toFloat() / total },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun PrepareStepCard(
  step: ActiveWorkoutStep.Prepare,
  onExerciseClick: (Exercise) -> Unit,
  onReady: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Valmistaudu", style = MaterialTheme.typography.labelLarge)
      Text(step.exercise.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      val equipment = step.exercise.equipment.orEmpty()
      if (equipment.isNotEmpty()) Text("Välineet: ${equipment.joinToString(", ")}")
      step.exercise.weightKg?.let { Text("Kuorma: ${it.toString().replace('.', ',')} kg") }
      OutlinedButton(onClick = { onExerciseClick(step.exercise) }, modifier = Modifier.fillMaxWidth()) {
        Text("Näytä liikeohje")
      }
      Button(onClick = onReady, modifier = Modifier.fillMaxWidth()) { Text("Olen valmis") }
    }
  }
}

@Composable
private fun PerformStepCard(
  step: ActiveWorkoutStep.Perform,
  upcoming: List<String>,
  onExerciseClick: (Exercise) -> Unit,
  onDone: () -> Unit,
  onSkip: () -> Unit,
) {
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text(step.exercise.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      val prescription = step.exercise.prescription()
      if (prescription.isNotBlank()) Text(prescription, style = MaterialTheme.typography.titleLarge)
      OutlinedButton(onClick = { onExerciseClick(step.exercise) }) { Text("Liikeohje") }
      if (step.exercise.durationSec != null) {
        ExerciseTimer(exercise = step.exercise, onAllRoundsCompleted = onDone)
      } else {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Liike valmis") }
      }
      OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Ohita liike") }
      if (upcoming.isNotEmpty()) {
        Text("Seuraavaksi", style = MaterialTheme.typography.labelLarge)
        Text(upcoming.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun CountdownStepCard(title: String, seconds: Int, next: String, onFinished: () -> Unit) {
  val context = LocalContext.current
  val deadline = rememberSaveable(title, seconds, next) { SystemClock.elapsedRealtime() + seconds * 1000L }
  var timeLeft by remember { mutableIntStateOf(seconds) }
  var delivered by rememberSaveable(deadline) { mutableStateOf(false) }

  LaunchedEffect(deadline) {
    while (!delivered) {
      val left = ceil((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 1000.0).toInt()
      timeLeft = left
      if (left <= 0) {
        delivered = true
        playTimerFinishedSound(context)
        vibrateTimerFinished(context)
        onFinished()
      } else {
        delay(200)
      }
    }
  }

  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Column(
      Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
          progress = { if (seconds == 0) 0f else timeLeft.toFloat() / seconds },
          modifier = Modifier.size(180.dp),
          strokeWidth = 14.dp,
        )
        Text("$timeLeft s", style = MaterialTheme.typography.displayMedium)
      }
      Text(next, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedButton(
        onClick = {
          delivered = true
          onFinished()
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Ohita lepo")
      }
    }
  }
}

@Composable
private fun FinishWorkoutCard(
  total: Int,
  skipped: List<SkippedMovement>,
  onSave: (Int, String?) -> Unit,
) {
  var rpe by rememberSaveable { mutableIntStateOf(5) }
  var feel by rememberSaveable { mutableStateOf<String?>(null) }
  Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Text("Treeni valmis", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      Text("Tehty ${total - skipped.size} / $total liikettä")
      if (skipped.isNotEmpty()) Text("Ohitettu: ${skipped.joinToString { it.name }}")
      Text("Rasittavuus (RPE) $rpe / 10", fontWeight = FontWeight.Bold)
      Slider(
        value = rpe.toFloat(),
        onValueChange = { rpe = it.toInt().coerceIn(1, 10) },
        valueRange = 1f..10f,
        steps = 8,
      )
      Text("Miltä tuntui?", fontWeight = FontWeight.Bold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Helppo", "Sopiva", "Raskas").forEach { option ->
          FilterChip(selected = feel == option, onClick = { feel = option }, label = { Text(option) })
        }
      }
      Button(onClick = { onSave(rpe, feel) }, modifier = Modifier.fillMaxWidth()) {
        Text("Tallenna treeni")
      }
    }
  }
}

private fun activeRound(step: ActiveWorkoutStep): Int =
  when (step) {
    is ActiveWorkoutStep.Prepare -> step.round
    is ActiveWorkoutStep.Perform -> step.round
    is ActiveWorkoutStep.Rest -> step.round
    is ActiveWorkoutStep.RoundBreak -> (step.nextRound - 1).coerceAtLeast(1)
    else -> 1
  }

@Composable
private fun rememberElapsedSeconds(startedAtMillis: Long): Long {
  var elapsed by remember(startedAtMillis) {
    mutableStateOf(((System.currentTimeMillis() - startedAtMillis) / 1000).coerceAtLeast(0))
  }
  LaunchedEffect(startedAtMillis) {
    while (true) {
      elapsed = ((System.currentTimeMillis() - startedAtMillis) / 1000).coerceAtLeast(0)
      delay(1_000)
    }
  }
  return elapsed
}

private fun formatElapsed(seconds: Long): String =
  "%02d:%02d".format(seconds / 60, seconds % 60)

private fun upcomingExercises(
  steps: List<ActiveWorkoutStep>,
  current: Int,
): List<String> =
  steps.drop(current + 1).filterIsInstance<ActiveWorkoutStep.Perform>().take(3).map { it.exercise.name }
