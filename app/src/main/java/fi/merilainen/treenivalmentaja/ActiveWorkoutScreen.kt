package fi.merilainen.treenivalmentaja

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutOutcome
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutStep
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
import fi.merilainen.treenivalmentaja.domain.SkippedMovement
import fi.merilainen.treenivalmentaja.domain.ActiveWorkoutPositionState
import fi.merilainen.treenivalmentaja.domain.buildActiveWorkoutSteps
import fi.merilainen.treenivalmentaja.domain.completedMovements
import fi.merilainen.treenivalmentaja.domain.key
import fi.merilainen.treenivalmentaja.domain.skippedMovements
import fi.merilainen.treenivalmentaja.domain.nextStep
import fi.merilainen.treenivalmentaja.domain.previousStep
import fi.merilainen.treenivalmentaja.domain.resumeIndex
import fi.merilainen.treenivalmentaja.domain.upcomingInRound
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
  val position by viewModel.activeWorkoutPosition.collectAsState()

  LaunchedEffect(sessionId) { viewModel.startActiveWorkout(sessionId) }

  if (workout == null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Harjoitusta ei löytynyt")
    }
    return
  }

  // Nothing is drawn until the stored position is known. Drawing the first movement first and
  // correcting it a moment later would tell someone mid-workout that they are starting over.
  val restored =
    when (val state = position) {
      is ActiveWorkoutPositionState.Loading -> {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
      }
      is ActiveWorkoutPositionState.Ready -> state.value
    }

  ActiveWorkoutContent(
    workout = workout,
    onClose = onClose,
    onExerciseClick = viewModel::openExerciseGuide,
    onComplete = { outcome ->
      viewModel.completeActiveWorkout(sessionId, outcome)
      onClose()
    },
    // A restored position means the workout was already under way, so the start summary is not
    // shown again — it is the answer to "what am I about to do", not to "where was I".
    initialOverviewVisible = restored == null,
    initialStepIndex = restored?.stepIndex ?: 0,
    initialSkippedKeys = restored?.skippedKeys.orEmpty(),
    resumed = restored != null,
    onPositionChange = { stepIndex, skippedKeys ->
      viewModel.saveActiveWorkoutPosition(sessionId, stepIndex, skippedKeys)
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
  initialSkippedKeys: List<String> = emptyList(),
  resumed: Boolean = false,
  onPositionChange: (Int, List<String>) -> Unit = { _, _ -> },
  trackElapsed: Boolean = true,
) {
  val steps =
    remember(workout.exercises, workout.rounds, workout.roundRestSec) {
      buildActiveWorkoutSteps(workout.exercises, workout.rounds, workout.roundRestSec)
    }
  var overviewVisible by rememberSaveable(workout.id) { mutableStateOf(initialOverviewVisible) }
  var skippedIds by rememberSaveable(workout.id, steps.size) { mutableStateOf(initialSkippedKeys) }
  // A stored index can name a skipped movement — skip one, walk back, leave, return — so a resume
  // moves forward off it rather than reopening the thing that was declined.
  var stepIndex by rememberSaveable(workout.id, steps.size) {
    mutableIntStateOf(
      if (resumed) steps.resumeIndex(initialStepIndex, initialSkippedKeys) else initialStepIndex
    )
  }
  val startedAt = rememberSaveable(workout.id) { System.currentTimeMillis() }
  val elapsedSec = if (trackElapsed) rememberElapsedSeconds(startedAt) else 0

  // Mirrored upwards on every change so the position outlives this screen. `rememberSaveable`
  // alone only survives the process being killed — the back arrow pops the destination, and that
  // takes its saved state with it.
  LaunchedEffect(workout.id, stepIndex, skippedIds) { onPositionChange(stepIndex, skippedIds) }

  val view = LocalView.current
  DisposableEffect(view) {
    val previous = view.keepScreenOn
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = previous }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
          ),
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
            onReady = { stepIndex = steps.nextStep(safeIndex, skippedIds) },
          )
        is ActiveWorkoutStep.Perform -> {
          PerformStepCard(
            step = step,
            onExerciseClick = onExerciseClick,
            onDone = { stepIndex = steps.nextStep(safeIndex, skippedIds) },
            onSkip = {
              // The new list has to exist before the jump is computed: the movement being skipped
              // is what the rest after it hangs on, and that rest is what we are stepping over.
              val keys = (skippedIds + step.key()).distinct()
              skippedIds = keys
              stepIndex = steps.nextStep(safeIndex, keys)
            },
          )
          UpcomingCard(upcoming = upcomingInRound(steps, safeIndex))
        }
        is ActiveWorkoutStep.Rest ->
          CountdownStepCard(
            title = "Lepo",
            seconds = step.seconds,
            next = "Seuraavaksi ${step.nextExerciseName}",
            onFinished = { stepIndex = steps.nextStep(safeIndex, skippedIds) },
          )
        is ActiveWorkoutStep.RoundBreak ->
          CountdownStepCard(
            title = "Kierrostauko",
            seconds = step.seconds,
            next = "Seuraavaksi kierros ${step.nextRound}",
            onFinished = { stepIndex = steps.nextStep(safeIndex, skippedIds) },
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

      // Skipped movements are invisible to this button. Stepping back onto one and being offered
      // it again as the next thing to do is the opposite of what skipping meant.
      val previous = steps.previousStep(safeIndex, skippedIds)
      if (previous != safeIndex && step !is ActiveWorkoutStep.Finish) {
        OutlinedButton(onClick = { stepIndex = previous }, modifier = Modifier.fillMaxWidth()) {
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
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      // The round on the left and the clock on the right, with the movement count under it.
      // The mockup carried the clock twice and had the two counts' names the other way round;
      // the labels here follow what the numbers actually count.
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Kierros $round / $rounds", fontWeight = FontWeight.Bold)
        Text("Kesto ${formatElapsed(elapsedSec)}", fontWeight = FontWeight.Bold)
      }
      Text(
        text = "Liikkeet $completed / $total",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(4.dp))
      // The track is named explicitly. Material 3 defaults it to `secondaryContainer`, which in
      // this palette is the green that means "done" — so an empty bar was drawn full-width in the
      // colour of completion, and "Liikkeet 0 / 6" sat above a bar that looked finished.
      LinearProgressIndicator(
        progress = { if (total == 0) 0f else completed.toFloat() / total },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        strokeCap = StrokeCap.Round,
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
      ExerciseIcon(step.exercise.name, size = 64.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
      Text(step.exercise.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
      val equipment = step.exercise.equipment.orEmpty()
      if (equipment.isNotEmpty()) Text("Välineet: ${equipment.joinToString(", ")}")
      step.exercise.weightKg?.let { Text("Kuorma: ${it.toString().replace('.', ',')} kg") }
      GuideButton(exercise = step.exercise, onExerciseClick = onExerciseClick)
      Button(onClick = onReady, modifier = Modifier.fillMaxWidth()) { Text("Olen valmis") }
    }
  }
}

@Composable
private fun PerformStepCard(
  step: ActiveWorkoutStep.Perform,
  onExerciseClick: (Exercise) -> Unit,
  onDone: () -> Unit,
  onSkip: () -> Unit,
) {
  // The movement is what the screen is for, so it is centred and everything else is arranged
  // around it: the figure first, then the name, then what to do — read top to bottom.
  OutlinedCard(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      ExerciseIcon(step.exercise.name, size = 72.dp, tint = MaterialTheme.colorScheme.onSurface)
      Text(
        step.exercise.name,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
      )
      val prescription = step.exercise.prescription(verbose = true)
      if (prescription.isNotBlank()) {
        // Same size as the name: this is read from the floor mid-movement, not glanced at from a
        // hand holding the phone, and "kuinka monta" is as essential there as "mikä liike".
        Text(
          prescription,
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      GuideButton(exercise = step.exercise, onExerciseClick = onExerciseClick)
      if (step.exercise.durationSec != null) {
        // A held movement finishes when its clock does, so there is no "Liike valmis" to place
        // beside the skip — the timer is the action.
        ExerciseTimer(exercise = step.exercise, onAllRoundsCompleted = onDone)
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Ohita liike") }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Ohita liike") }
          Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Liike valmis") }
        }
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
          trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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

/**
 * The guide, in the same place under the same name on every step.
 *
 * It used to be a full-width "Näytä liikeohje" on the preparation screen and a small "Liikeohje"
 * on the movement itself — two names and two sizes for one control, which reads as two different
 * things. One name, and pushed to the right so it sits beside the movement rather than in the path
 * of the button that advances it: the guide is a thing you may want, never the next thing to do.
 */
@Composable
private fun GuideButton(exercise: Exercise, onExerciseClick: (Exercise) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Button(
      onClick = { onExerciseClick(exercise) },
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
      Text("Liikeohje")
    }
  }
}

/**
 * What comes after this movement, as its own card.
 *
 * It was a line at the bottom of the movement's own card, where "now" and "next" were one block of
 * text and the eye had to separate them. There is room on the screen for the distinction to be made
 * by the layout instead.
 */
@Composable
private fun UpcomingCard(upcoming: List<String>) {
  if (upcoming.isEmpty()) return
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "Seuraavaksi:",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ExerciseIcon(upcoming.first(), size = 32.dp)
        Text(upcoming.first(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      }
      // The rest of the round, quietly: the principle is one thing at a time, so what follows the
      // next movement is context rather than an instruction.
      val rest = upcoming.drop(1)
      if (rest.isNotEmpty()) {
        Text(
          rest.joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

