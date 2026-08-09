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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
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
                val perRound =
                    if (fromPlan) workout.exercises.size else parsedWorkout.exercises.size
                val total = rounds * perRound

                // How far down the list the session has got, as one number.
                //
                // A workout is a sequence, not a set of independent boxes: the third round of an
                // exercise cannot be done before the second, and a movement ticked off by mistake
                // is undone by walking back, not by reaching into the middle. One counter says
                // all of that — a row is done below it, current at it, and not yet reachable
                // above it — and there is no way to represent an order that never happened.
                var completed by rememberSaveable(workout.id) { mutableIntStateOf(0) }
                // The plan can change under a started session ("Kevyempi versio" swaps the list),
                // so the counter is read through a clamp rather than trusted blindly.
                val done = completed.coerceIn(0, total)

                for (round in 1..rounds) {
                    if (rounds > 1) {
                        Text(
                            text = "Kierros $round",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    for (position in 0 until perRound) {
                        val index = (round - 1) * perRound + position
                        // Keyed by its place in the sequence so each round gets its own row and
                        // its own clock, rather than inheriting the previous round's finished one.
                        key(round, position) {
                            GuidedExerciseRow(
                                exercise =
                                    if (fromPlan) workout.exercises[position]
                                    else parsedWorkout.exercises[position].asExercise(),
                                checked = index < done,
                                // Only the next movement can be ticked, and only the last ticked
                                // one can be unticked.
                                enabled = index == done || index == done - 1,
                                isCurrent = index == done,
                                onCheckedChange = { completed = if (it) index + 1 else index },
                                onExerciseClick = onExerciseClick.takeIf { fromPlan },
                            )
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
 * The clock only appears on the movement you are actually on. That is what makes the sequence
 * real rather than advisory — and it is also why there is no "Valmis / Alusta" left to read: the
 * last round ticks the row, the row stops being current, and the clock goes with it. Untick the
 * row and it comes back at the first side, because the clock's state lives only as long as it is
 * on screen.
 */
@Composable
private fun GuidedExerciseRow(
    exercise: Exercise,
    checked: Boolean,
    enabled: Boolean,
    isCurrent: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onExerciseClick: ((Exercise) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
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
            if (isCurrent && exercise.durationSec != null) {
                Spacer(modifier = Modifier.height(4.dp))
                // The last round ticks the movement off, so finishing the clock and marking it
                // done are the same act rather than two things to remember.
                ExerciseTimer(
                    exercise = exercise,
                    onAllRoundsCompleted = { onCheckedChange(true) },
                )
            }
        }
    }
}

/**
 * A movement read out of the description, dressed as one the plan wrote.
 *
 * Only the duration survives the guess — `parseStrengthDescription` decides a movement is timed by
 * finding "lankku" in its name — but it is enough to hand the same clock to plans written before
 * the `exercises` array existed, instead of keeping a second timer alive for them.
 */
private fun ParsedExercise.asExercise(): Exercise =
    Exercise(name = name, durationSec = plankDurationSeconds?.takeIf { isPlank })

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
