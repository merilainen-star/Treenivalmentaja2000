package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.WorkoutType

/**
 * A workout's content, read-only: what it is, not what to do about it.
 *
 * Shared by the Today card and the expanded Week card so the same session never reads two
 * different ways. Today additionally offers a checkable version once a strength session has been
 * started; that one stays in `TodayScreen`, because it carries per-exercise state.
 *
 * There are two sources for the movements, and the better one wins. A plan that carries a real
 * `exercises` array is shown from it, which is the only way to know that a hold is timed and how
 * many times its clock has to run. A plan that does not is read back out of the description by
 * `parseStrengthDescription`, which decides what is a movement by counting commas — a guess, kept
 * only so plans written before the array was used still show something.
 *
 * The surrounding prose comes from the description either way, so warm-ups and rest instructions
 * survive.
 *
 * @param onExerciseClick opens the exercise guide. `null` leaves the rows inert, which is what
 *   the screenshot tests and any caller without a ViewModel get: the guide is an extra, and a row
 *   that cannot open one should not offer to.
 */
@Composable
fun WorkoutDetails(
    workout: Workout,
    modifier: Modifier = Modifier,
    onExerciseClick: ((Exercise) -> Unit)? = null,
) {
    val parsed = remember(workout.description) { parseStrengthDescription(workout.description) }
    val fromPlan = workout.exercises.isNotEmpty()
    val fromText = workout.type == WorkoutType.STRENGTH && parsed.exercises.isNotEmpty()

    Column(modifier = modifier) {
        if (!fromPlan && !fromText) {
            Text(text = workout.description, style = MaterialTheme.typography.bodyLarge)
        } else {
            if (parsed.intro.isNotBlank()) {
                Text(text = parsed.intro, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }

            val rounds = if (fromPlan) workout.rounds else parsed.rounds
            for (round in 1..rounds) {
                if (rounds > 1) {
                    Text(
                        text = "Kierros $round",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (fromPlan) {
                    workout.exercises.forEach { exercise ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (onExerciseClick == null) Modifier
                                    else Modifier.clickable(
                                        role = Role.Button,
                                        onClickLabel = "Näytä liikeohje",
                                        onClick = { onExerciseClick(exercise) },
                                    )
                                )
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "• ${exercise.name}",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (onExerciseClick != null) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        // The row carries the label; naming the icon too would
                                        // make TalkBack announce the same thing twice.
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            val prescription = exercise.prescription()
                            if (prescription.isNotEmpty()) {
                                Text(
                                    text = prescription,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                            if (exercise.durationSec != null) {
                                // Keyed by round so each round gets its own clock rather than
                                // inheriting the previous round's finished one.
                                key(round, exercise.name) {
                                    ExerciseTimer(
                                        exercise = exercise,
                                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    parsed.exercises.forEach { exercise ->
                        Text(
                            text = "• ${exercise.name}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            if (parsed.outro.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = parsed.outro, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (workout.appliedLighterVariant) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Kevennetty versio käytössä.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
