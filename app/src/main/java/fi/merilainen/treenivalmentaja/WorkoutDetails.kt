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
import androidx.compose.ui.text.font.FontWeight
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

            // One round's movements, once — not the same list repeated per round.
            //
            // A circuit is the same movements done again, so printing them three times says
            // nothing the count does not already say, and it pushed everything else off the card.
            // How many rounds there are is stated once, above the list, and the Today card's stat
            // row says it a second time.
            // Only for a plan's own movements. A session parsed out of its description already
            // says the rounds in its own prose above this line — "3 kierrosta." — and stating it
            // again in different words reads as two different facts.
            val rounds = if (fromPlan) workout.rounds else parsed.rounds
            if (fromPlan && rounds > 1) {
                Text(
                    text = "$rounds kierrosta seuraavista liikkeistä",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            run {
                if (fromPlan) {
                    workout.exercises.forEach { exercise ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            // Figure, then name over prescription, then the guide at the far
                            // right. The two lines belong to one movement, so they are one block
                            // beside the figure rather than two rows that happen to be adjacent.
                            ExerciseRow(
                                exercise = exercise,
                                onClick = onExerciseClick?.let { open -> { open(exercise) } },
                            )
                            // No clock here, deliberately. This rendering is also the expanded
                            // Week row, where it offered to start a hold for a session two days
                            // away — and on the Today card it was a second, unsequenced clock
                            // beside the one the started workout provides. A hold's duration is
                            // still on the line above; running it belongs to doing the session,
                            // which is what "Aloita ohjattu treeni" is for.
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

/**
 * One movement on a list: the category figure, the name with what to do under it, and the guide.
 *
 * The guide sits at the far right rather than beside the name, so a long name cannot push it off
 * the row and every row's guide is in the same place.
 */
@Composable
internal fun ExerciseRow(
    exercise: Exercise,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = "Näytä liikeohje",
                    onClick = onClick,
                )
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExerciseIcon(exercise.name, size = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            val prescription = exercise.prescription()
            if (prescription.isNotEmpty()) {
                Text(
                    text = prescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.Info,
                // The row carries the label; naming the icon too would make TalkBack announce
                // the same thing twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A movement's name, and the way in to its guide.
 *
 * Shared by the read-only list and the started workout's checklist so the same movement offers
 * the same thing in both. Only the name carries the tap: the row below it holds the clock's own
 * buttons, and a movement should not open a guide because you reached for "Käynnistä".
 */
@Composable
internal fun ExerciseNameRow(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.then(
            if (onClick == null) Modifier
            else Modifier.clickable(
                role = Role.Button,
                onClickLabel = "Näytä liikeohje",
                onClick = onClick,
            )
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.Info,
                // The row carries the label; naming the icon too would make TalkBack announce
                // the same thing twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
