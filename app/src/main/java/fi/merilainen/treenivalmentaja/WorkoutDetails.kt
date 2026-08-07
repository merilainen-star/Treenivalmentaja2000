package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.WorkoutType

/**
 * A workout's content, read-only: what it is, not what to do about it.
 *
 * Shared by the Today card and the expanded Week card so the same session never reads two
 * different ways. Today additionally offers a checkable, timed version once a strength session
 * has been started; that one stays in `TodayScreen`, because it carries per-exercise state.
 *
 * A strength description is free text that `parseStrengthDescription` splits into an intro, the
 * movements and an outro. When it finds no movements — every running session, and any strength
 * session whose description is not a comma-separated list — the description is shown as written.
 */
@Composable
fun WorkoutDetails(workout: Workout, modifier: Modifier = Modifier) {
    val parsed = remember(workout.description) { parseStrengthDescription(workout.description) }
    val hasExercises = workout.type == WorkoutType.STRENGTH && parsed.exercises.isNotEmpty()

    Column(modifier = modifier) {
        if (hasExercises) {
            if (parsed.intro.isNotBlank()) {
                Text(text = parsed.intro, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))
            }
            for (round in 1..parsed.rounds) {
                if (parsed.rounds > 1) {
                    Text(
                        text = "Kierros $round",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                parsed.exercises.forEach { exercise ->
                    Text(text = "• ${exercise.name}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (parsed.outro.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = parsed.outro, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text(text = workout.description, style = MaterialTheme.typography.bodyLarge)
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
