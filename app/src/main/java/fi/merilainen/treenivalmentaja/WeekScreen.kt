package fi.merilainen.treenivalmentaja

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import fi.merilainen.treenivalmentaja.ui.theme.ColorBlue
import fi.merilainen.treenivalmentaja.ui.theme.ColorGreen
import fi.merilainen.treenivalmentaja.ui.theme.ColorRed

@Composable
fun WeekScreen(viewModel: WorkoutViewModel) {
    val workouts by viewModel.workouts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Viikon ohjelma",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(7) { dayIndex ->
                val dayWorkouts = workouts.filter { it.dayOffset == dayIndex }
                val dayName = when (dayIndex) {
                    0 -> "Tänään"
                    1 -> "Huomenna"
                    2 -> "Keskiviikko"
                    3 -> "Torstai"
                    4 -> "Perjantai"
                    5 -> "Lauantai"
                    6 -> "Sunnuntai"
                    else -> ""
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = dayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    if (dayWorkouts.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                text = "Lepo",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        dayWorkouts.forEach { workout ->
                            WorkoutCardWeek(workout)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One session in the week list, collapsed to a single row until it is tapped.
 *
 * Expansion state lives here so the caller does not have to track it; the keyed
 * [rememberSaveable] survives scrolling the list and process death, and resets if the row is
 * reused for a different session. The stateless overload below takes the state as a parameter,
 * which is what the screenshot tests drive.
 */
@Composable
fun WorkoutCardWeek(workout: Workout) {
    var expanded by rememberSaveable(workout.id) { mutableStateOf(false) }
    WorkoutCardWeek(workout = workout, expanded = expanded, onToggle = { expanded = !expanded })
}

@Composable
fun WorkoutCardWeek(workout: Workout, expanded: Boolean, onToggle: () -> Unit) {
    val indicatorColor = when (workout.type) {
        WorkoutType.RUNNING -> ColorBlue
        WorkoutType.STRENGTH -> ColorGreen
        WorkoutType.SKIING -> ColorRed
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron"
    )

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workout.type.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (workout.movedHere) {
                    Text(
                        text = "(Siirretty tälle päivälle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (workout.status != SessionStatus.PLANNED) {
                    Text(
                        text = workout.status.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = workout.time,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${workout.durationMin} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Piilota tiedot" else "Näytä tiedot",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .rotate(chevronRotation)
            )
        }

        // expandVertically animates the height from zero, so the rows below are pushed down as
        // the details unroll rather than jumping into place.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                WorkoutDetails(workout)
            }
        }
      }
    }
}
