package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.RunLap

/**
 * The laps the watch recorded, behind a toggle — the app's copy of the lap table in the Suunto
 * app, one line per lap: time, distance, pace, heart rate.
 *
 * **Closed by default.** An interval session has eighteen laps, and the card it sits in is read
 * for the summary above it; the laps are there for the moment someone wants to see how the
 * repetitions went. Nothing is drawn for fewer than two laps — a single lap is the whole run, and
 * already on the line above.
 */
@Composable
fun RunLapsSection(
    laps: List<RunLap>,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    /** Only for a screenshot of the opened list; the screens always start closed. */
    initiallyOpen: Boolean = false,
) {
    if (laps.size < 2) return
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text(
                text = "Kierrokset (${laps.size}) " + if (open) "▴" else "▾",
                style = style,
            )
        }
        if (open) {
            laps.forEach { lap ->
                Text(
                    text = lapLine(lap),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `7. 1:56,6 · 400 m · 4:51 /km · syke 150` */
internal fun lapLine(lap: RunLap): String {
    val parts = buildList {
        add(lap.durationText)
        lap.distanceText?.let { add(it) }
        lap.paceSecPerKm?.let { add("${it / 60}:${(it % 60).toString().padStart(2, '0')} /km") }
        lap.avgHeartRate?.let { add("syke $it") }
    }
    return "${lap.index}. ${parts.joinToString(" · ")}"
}
