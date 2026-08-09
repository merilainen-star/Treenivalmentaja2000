package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.PaddingValues
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.ui.theme.ColorBlue
import fi.merilainen.treenivalmentaja.ui.theme.ColorGray
import fi.merilainen.treenivalmentaja.ui.theme.ColorGreen
import fi.merilainen.treenivalmentaja.ui.theme.ColorRed
import fi.merilainen.treenivalmentaja.ui.theme.ColorYellow

@Composable
fun TodayScreen(viewModel: WorkoutViewModel) {
    val workouts by viewModel.workouts.collectAsState()
    val guideState by viewModel.guideState.collectAsState()

    // No automatic checkMissedSessions() here. Today is the start destination, so this ran on
    // every launch and rewrote the calendar — see WorkoutViewModel.checkMissedSessions.

    val todayWorkouts = workouts.filter { it.dayOffset == 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Tänään",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        RecoveryCard(
            onSickClicked = { viewModel.markSick() },
            onRecoveredClicked = { viewModel.markRecovered() }
        )

        if (todayWorkouts.isNotEmpty()) {
            todayWorkouts.forEach { workout ->
                WorkoutCardToday(
                    workout = workout,
                    onStatusChange = { newStatus ->
                        viewModel.updateWorkoutStatus(workout.id, newStatus)
                    },
                    onMoveToTomorrow = {
                        viewModel.moveWorkoutToTomorrow(workout.id)
                    },
                    onExerciseClick = { viewModel.openExerciseGuide(it) }
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Ei treeniä tälle päivälle. Nauti lepopäivästä!",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Renders in its own window, so it costs the scrolling column no layout at all.
        guideState?.let { state ->
            ExerciseGuideSheet(
                state = state,
                onRetry = { viewModel.retryExerciseGuide() },
                onSelectSuggestion = { viewModel.selectGuideSuggestion(it) },
                onDismiss = { viewModel.closeExerciseGuide() },
            )
        }
    }
}

/**
 * The two things the app can actually be told about your condition.
 *
 * It used to sit under a coloured indicator reading "Palautuminen: Kohtalainen" with the advice
 * "Kevyempi versio voi olla järkevä". Nothing fed either of them: the value was a constant set in
 * two places, both to the same thing, and the Oura tables it was waiting for have no writer. So
 * the app repeated the same reading every day and nudged towards a lighter session on all of
 * them — advice with nothing behind it, wearing the clothes of a measurement.
 *
 * The buttons were always real. They drive the training engine's illness pause and its graduated
 * return, so this is what is left when the part that only looked informative is taken away. A
 * recovery indicator belongs here again the day Oura can fill one in.
 */
@Composable
fun RecoveryCard(
    onSickClicked: () -> Unit,
    onRecoveredClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSickClicked,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sairastuin")
                }
                Button(
                    onClick = onRecoveredClicked,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tervehdyin")
                }
            }
        }
    }
}

@Composable
fun WorkoutCardToday(
    workout: Workout,
    onStatusChange: (SessionStatus) -> Unit,
    onMoveToTomorrow: () -> Unit,
    onExerciseClick: ((Exercise) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = workout.type.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                WorkoutStatusBadge(workout.status)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Suunniteltu klo ${workout.time}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${workout.durationMin} min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            val isInteractive = workout.type == WorkoutType.STRENGTH && workout.status == SessionStatus.STARTED
            val parsedWorkout = remember(workout.description) { parseStrengthDescription(workout.description) }
            // The plan's own movements when it has them, exactly as the read-only list uses them.
            // Reading the description instead was what made a started workout lose the guide links
            // and hand a per-side hold a single clock.
            val fromPlan = workout.exercises.isNotEmpty()
            val guided = isInteractive && (fromPlan || parsedWorkout.exercises.isNotEmpty())

            if (guided) {
                if (parsedWorkout.intro.isNotBlank()) {
                    Text(
                        text = parsedWorkout.intro,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                val rounds = if (fromPlan) workout.rounds else parsedWorkout.rounds
                for (round in 1..rounds) {
                    if (rounds > 1) {
                        Text(
                            text = "Kierros $round",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (fromPlan) {
                        workout.exercises.forEachIndexed { index, exercise ->
                            // Keyed by round so each round gets its own checkbox and its own
                            // clock, rather than inheriting the previous round's finished ones.
                            key(round, index, exercise.name) {
                                GuidedExerciseRow(
                                    exercise = exercise,
                                    onExerciseClick = onExerciseClick,
                                )
                            }
                        }
                    } else {
                        parsedWorkout.exercises.forEachIndexed { index, exercise ->
                            key(round, index, exercise.name) {
                                var checked by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { checked = it }
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(text = exercise.name, style = MaterialTheme.typography.bodyLarge)
                                        if (exercise.isPlank && exercise.plankDurationSeconds != null && !checked) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            PlankTimer(durationSeconds = exercise.plankDurationSeconds)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (parsedWorkout.outro.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = parsedWorkout.outro,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                // Same read-only rendering the expanded Week card uses.
                WorkoutDetails(workout, onExerciseClick = onExerciseClick)
            }

            if (guided && workout.appliedLighterVariant) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Kevennetty versio käytössä.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons. Only offered while the session is still open — a completed,
            // skipped or cancelled session has no legal transition left.
            if (workout.status.isOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (workout.type == WorkoutType.STRENGTH && workout.status != SessionStatus.STARTED) {
                        Button(
                            onClick = { onStatusChange(SessionStatus.STARTED) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Aloita ohjattu treeni")
                        }
                    } else if (workout.type == WorkoutType.STRENGTH && workout.status == SessionStatus.STARTED) {
                        Button(
                            onClick = { onStatusChange(SessionStatus.COMPLETED) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Valmis")
                        }
                    } else {
                        Button(
                            onClick = { onStatusChange(SessionStatus.COMPLETED) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Merkitse tehdyksi")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onStatusChange(SessionStatus.REPLACED_WITH_LIGHTER_VERSION)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !workout.appliedLighterVariant,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Kevyempi versio", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onStatusChange(SessionStatus.SKIPPED) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Ohita")
                        }
                    }
                    OutlinedButton(
                        onClick = onMoveToTomorrow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Siirrä huomiselle")
                    }
                }
            }
        }
    }
}

/**
 * One movement of a started workout: tick it off, see what it asks for, open its guide, and run
 * its clock as many times as the movement actually needs.
 *
 * The clock is [ExerciseTimer], the same one the read-only list uses, so a hold done per side asks
 * for both sides and only calls itself done when both have run. The old checklist used a
 * single-shot timer that decided a movement was timed by looking for "lankku" in its name, which
 * is why a side plank offered one clock for two sides.
 */
@Composable
private fun GuidedExerciseRow(exercise: Exercise, onExerciseClick: ((Exercise) -> Unit)?) {
    var checked by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            ExerciseNameRow(
                text = exercise.name,
                onClick = onExerciseClick?.let { open -> { open(exercise) } },
            )
            val prescription = exercise.prescription()
            if (prescription.isNotEmpty()) {
                Text(
                    text = prescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Ticked off means done, so the clock goes rather than inviting another run.
            if (exercise.durationSec != null && !checked) {
                Spacer(modifier = Modifier.height(4.dp))
                ExerciseTimer(exercise = exercise)
            }
        }
    }
}

@Composable
fun WorkoutStatusBadge(status: SessionStatus) {
    val (color, textColor) = when (status) {
        SessionStatus.PLANNED -> ColorGray.copy(alpha = 0.2f) to ColorGray
        SessionStatus.NOTIFIED -> ColorBlue.copy(alpha = 0.2f) to ColorBlue
        SessionStatus.STARTED -> ColorBlue.copy(alpha = 0.2f) to ColorBlue
        SessionStatus.COMPLETED -> ColorGreen.copy(alpha = 0.2f) to ColorGreen
        SessionStatus.SKIPPED -> ColorRed.copy(alpha = 0.2f) to ColorRed
        SessionStatus.RESCHEDULED -> ColorGray.copy(alpha = 0.2f) to ColorGray
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION ->
            ColorYellow.copy(alpha = 0.2f) to Color(0xFFF57F17)
        SessionStatus.PAUSED_DUE_TO_ILLNESS -> ColorYellow.copy(alpha = 0.2f) to Color(0xFFF57F17)
        SessionStatus.CANCELLED -> ColorGray.copy(alpha = 0.2f) to ColorGray
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.title,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

data class ParsedWorkout(
    val intro: String,
    val exercises: List<ParsedExercise>,
    val outro: String,
    val rounds: Int = 1
)

data class ParsedExercise(
    val name: String,
    val isPlank: Boolean,
    val plankDurationSeconds: Int?
)

fun extractDuration(text: String): Int? {
    val match = Regex("""(\d+)\s*s""").find(text.lowercase())
    return match?.groupValues?.get(1)?.toIntOrNull()
}

fun extractRounds(text: String): Int {
    val match = Regex("""(\d+)\s*kierros""").find(text.lowercase())
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
}

fun parseStrengthDescription(desc: String): ParsedWorkout {
    val rounds = extractRounds(desc)
    val cleanDesc = desc.replace("\\,", ",")
    val parts = cleanDesc.split(Regex("""(?<=\.)\s+|\n+""")).filter { it.isNotBlank() }
    
    val exerciseSentence = parts.maxByOrNull { it.count { c -> c == ',' } }
    
    if (exerciseSentence == null || exerciseSentence.count { it == ',' } == 0) {
        val exercises = parts.map { 
            ParsedExercise(it.trim(), it.lowercase().contains("lankku"), extractDuration(it)) 
        }
        return ParsedWorkout("", exercises, "", rounds)
    }
    
    val intro = parts.takeWhile { it != exerciseSentence }.joinToString(" ")
    val outro = parts.takeLastWhile { it != exerciseSentence }.joinToString(" ")
    
    val exerciseStrings = exerciseSentence.removeSuffix(".").split(",")
    val exercises = exerciseStrings.map { ex -> 
        val name = ex.trim()
        val lower = name.lowercase()
        ParsedExercise(name, lower.contains("lankku"), extractDuration(name))
    }
    
    return ParsedWorkout(intro, exercises, outro, rounds)
}


@Composable
fun PlankTimer(durationSeconds: Int) {
    var timeLeft by remember { mutableIntStateOf(durationSeconds) }
    var isRunning by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    LaunchedEffect(isRunning, showDialog) {
        if (isRunning && showDialog) {
            while (timeLeft > 0) {
                delay(1000)
                timeLeft--
            }
            isRunning = false
            try {
                val notification = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val r = android.media.RingtoneManager.getRingtone(context, notification)
                r.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            showDialog = false
        }
    }
    
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (timeLeft > 0) {
            Text(
                text = "${timeLeft} s",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = { 
                    showDialog = true
                    isRunning = true 
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Aloita")
            }
        } else {
            Text("Aika täysi!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
    
    if (showDialog) {
        Dialog(onDismissRequest = { 
            showDialog = false
            isRunning = false 
        }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Lankku",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    val progress by animateFloatAsState(
                        targetValue = timeLeft.toFloat() / durationSeconds.toFloat(),
                        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
                        label = "progress"
                    )
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(240.dp),
                            strokeWidth = 16.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                        Text(
                            text = "$timeLeft",
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { 
                            showDialog = false
                            isRunning = false 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Keskeytä")
                    }
                }
            }
        }
    }
}
