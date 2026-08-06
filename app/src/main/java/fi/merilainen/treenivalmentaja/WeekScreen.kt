package fi.merilainen.treenivalmentaja

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun WorkoutCardWeek(workout: Workout) {
    val indicatorColor = when (workout.type) {
        WorkoutType.RUNNING -> ColorBlue
        WorkoutType.STRENGTH -> ColorGreen
        WorkoutType.SKIING -> ColorRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
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
        }
    }
}
