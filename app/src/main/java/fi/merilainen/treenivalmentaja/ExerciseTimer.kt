package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.Exercise
import kotlinx.coroutines.delay

/**
 * How many times an exercise's clock has to be run, and what to call each run.
 *
 * A hold done per side needs the clock twice, once for each side — running it once and calling
 * the exercise done is wrong, and doing it in your head while holding a side plank is worse.
 * `sets` multiplies the same way.
 *
 * This reads the plan's own fields rather than the exercise's name. The previous behaviour
 * decided an exercise was timed if its name contained "lankku", which is why a 30-second hip
 * flexor stretch got no clock at all, and why nothing ever ran twice.
 */
internal fun Exercise.timedRounds(): List<String> {
    if (durationSec == null) return emptyList()
    return when {
        perSide == true -> listOf("Vasen", "Oikea")
        (sets ?: 1) > 1 -> (1..sets!!).map { "Sarja $it" }
        else -> listOf("")
    }
}

/**
 * The clock for one timed exercise, run once per entry in [timedRounds].
 *
 * Each round is ticked off as it finishes, so the exercise is only done when they all are and
 * there is never a question of which side is still owed.
 */
@Composable
fun ExerciseTimer(exercise: Exercise, modifier: Modifier = Modifier) {
    val seconds = exercise.durationSec ?: return
    val rounds = remember(exercise) { exercise.timedRounds() }
    if (rounds.isEmpty()) return

    var completed by remember(exercise) { mutableIntStateOf(0) }
    var timeLeft by remember(exercise) { mutableIntStateOf(seconds) }
    var running by remember(exercise) { mutableStateOf(false) }

    LaunchedEffect(running, completed) {
        if (!running) return@LaunchedEffect
        while (timeLeft > 0) {
            delay(1000)
            timeLeft--
        }
        running = false
        completed++
        timeLeft = seconds
    }

    val allDone = completed >= rounds.size

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (allDone) {
            Text(
                text = "Valmis",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = { completed = 0; timeLeft = seconds }) { Text("Alusta") }
            return@Row
        }

        // A blank label means a single unnamed round, so nothing is announced for "lankku 30 s".
        val label = rounds[completed]
        if (label.isNotEmpty()) {
            Text(
                text = "$label ${completed + 1}/${rounds.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "$timeLeft s",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Button(
            onClick = { running = !running },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(if (running) "Tauko" else "Käynnistä")
        }
    }
}
