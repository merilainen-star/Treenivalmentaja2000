package fi.merilainen.treenivalmentaja

import android.content.Context
import android.media.RingtoneManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
 * What to actually do, in the shorthand a gym log uses: `4 × 10 · 55 kg`, `10 / puoli`, `30 s`.
 *
 * Empty when the plan says nothing beyond the name, so the caller can leave the line out rather
 * than print a stray separator.
 */
internal fun Exercise.prescription(): String {
    // A ramp is listed set by set: the weights are the whole point of writing it that way.
    setPlan?.takeIf { it.isNotEmpty() }?.let { plan ->
        return plan.joinToString(" · ") { set ->
            val work = when {
                set.reps != null -> "× ${set.reps}"
                set.durationSec != null -> "${set.durationSec} s"
                else -> ""
            }
            listOfNotNull(set.weightKg?.let { formatKg(it) }, work.ifEmpty { null })
                .joinToString(" ")
        }
    }

    val work = when {
        reps != null -> "$reps"
        repsMin != null && repsMax != null -> "$repsMin–$repsMax"
        durationSec != null -> "$durationSec s"
        else -> null
    } ?: return ""

    val withSets = if ((sets ?: 1) > 1) "$sets × $work" else work
    val withSide = if (perSide == true) "$withSets / puoli" else withSets
    return listOfNotNull(withSide, weightKg?.let { formatKg(it) }).joinToString(" · ")
}

/** 55.0 reads as "55 kg", 17.5 as "17,5 kg" — Finnish decimal comma, no trailing zero. */
private fun formatKg(kg: Double): String {
    val text = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString().replace('.', ',')
    return "$text kg"
}

/**
 * The device's notification sound, for the moment a hold ends.
 *
 * A hold is done with your eyes shut or your face at the floor, so the clock reaching zero has to
 * be audible. Failing to ring is not worth crashing over: a silenced phone, a missing default
 * tone or a locked audio focus all end up here.
 */
internal fun playTimerFinishedSound(context: Context) {
    runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, uri)?.play()
    }
}

/**
 * The clock for one timed exercise, run once per entry in [timedRounds].
 *
 * The countdown happens in a dialog with a ring that empties as it goes: a hold is done looking at
 * the floor, so the number has to be readable at arm's length and the end has to be audible.
 * Running it as a line of small text was a regression — it looked fine on a screenshot and was
 * useless in a plank.
 *
 * Each round is ticked off as it finishes, so the exercise is only done when they all are and
 * there is never a question of which side is still owed.
 *
 * @param onAllRoundsCompleted called when the last round ends. The guided checklist uses it to
 *   mark the movement done, which is also what removes this clock from the screen — so there is
 *   no "Valmis / Alusta" state to read and dismiss. Where nothing is listening (the read-only
 *   list) the clock says it is finished and offers to start over.
 */
@Composable
fun ExerciseTimer(
    exercise: Exercise,
    modifier: Modifier = Modifier,
    onAllRoundsCompleted: (() -> Unit)? = null,
) {
    val seconds = exercise.durationSec ?: return
    val rounds = remember(exercise) { exercise.timedRounds() }
    if (rounds.isEmpty()) return

    val context = LocalContext.current
    var completed by remember(exercise) { mutableIntStateOf(0) }
    var running by remember(exercise) { mutableStateOf(false) }

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
            TextButton(onClick = { completed = 0 }) { Text("Alusta") }
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
            text = "$seconds s",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Button(
            onClick = { running = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Käynnistä")
        }
    }

    if (running && !allDone) {
        CountdownDialog(
            title = exercise.name,
            round = rounds[completed].takeIf { it.isNotEmpty() }
                ?.let { "$it ${completed + 1}/${rounds.size}" },
            seconds = seconds,
            onCancelled = { running = false },
            onFinished = {
                playTimerFinishedSound(context)
                running = false
                completed++
                if (completed >= rounds.size) onAllRoundsCompleted?.invoke()
            },
        )
    }
}

/**
 * The countdown itself: one number, one ring, one way out.
 *
 * Keyed on nothing but its own composition — it exists only while a round is running, so opening
 * it again always starts from the top.
 */
@Composable
private fun CountdownDialog(
    title: String,
    round: String?,
    seconds: Int,
    onCancelled: () -> Unit,
    onFinished: () -> Unit,
) {
    var timeLeft by remember { mutableIntStateOf(seconds) }

    LaunchedEffect(Unit) {
        while (timeLeft > 0) {
            delay(1000)
            timeLeft--
        }
        onFinished()
    }

    Dialog(onDismissRequest = onCancelled) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                if (round != null) {
                    Text(
                        text = round,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                val progress by animateFloatAsState(
                    targetValue = timeLeft.toFloat() / seconds.toFloat(),
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
                Button(onClick = onCancelled, modifier = Modifier.fillMaxWidth()) {
                    Text("Keskeytä")
                }
            }
        }
    }
}
