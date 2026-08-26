package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import fi.merilainen.treenivalmentaja.ui.theme.ColorBlue
import fi.merilainen.treenivalmentaja.ui.theme.ColorGreen
import fi.merilainen.treenivalmentaja.ui.theme.ColorRed
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun MetricRing(
  label: String,
  value: Int?,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(86.dp)) {
      CircularProgressIndicator(
        progress = { (value ?: 0).coerceIn(0, 100) / 100f },
        modifier = Modifier.size(82.dp),
        strokeWidth = 8.dp,
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      )
      Text(
        text = value?.toString() ?: "–",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/**
 * The week, seven days wide.
 *
 * This replaced a full month grid. The month took a third of the screen to answer a question nobody
 * was asking on this screen — the list underneath is what people read, and the grid was pushing it
 * below the fold. A week is what a training plan is written in anyway.
 *
 * Under each day sit up to three dots, coloured by what kind of session was on that day. The colour
 * is the same one the session's own row carries, so the strip and the list agree without a legend.
 */
@Composable
internal fun WeekStrip(
  today: LocalDate,
  workouts: List<Workout>,
  modifier: Modifier = Modifier,
  selectedDate: LocalDate? = null,
  onDateClick: ((LocalDate) -> Unit)? = null,
) {
  val anchor = selectedDate ?: today
  var weekOffset by rememberSaveable(today) { mutableIntStateOf(0) }
  val monday =
    anchor.minusDays((anchor.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
      .plusWeeks(weekOffset.toLong())
  val days = List(7) { monday.plusDays(it.toLong()) }
  val byDate = workouts.groupBy { today.plusDays(it.dayOffset.toLong()) }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(onClick = { weekOffset-- }) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Edellinen viikko")
      }
      Text(
        text =
          monday.month
            .getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("fi-FI"))
            .replaceFirstChar { it.uppercase(Locale.forLanguageTag("fi-FI")) } + " ${monday.year}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      IconButton(onClick = { weekOffset++ }) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Seuraava viikko")
      }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      days.forEach { date ->
        val sessions = byDate[date].orEmpty()
        val isToday = date == today
        val isSelected = date == selectedDate
        Column(
          modifier =
            Modifier
              .weight(1f)
              .clip(RoundedCornerShape(10.dp))
              .background(
                when {
                  isToday -> MaterialTheme.colorScheme.primary
                  else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
              )
              .then(
                if (isSelected && !isToday) {
                  Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                } else {
                  Modifier
                }
              )
              .then(
                if (onDateClick != null) {
                  Modifier.clickable(
                    onClickLabel = "Näytä ${date.dayOfMonth}. päivän harjoitukset",
                    role = Role.Button,
                  ) { onDateClick(date) }
                } else {
                  Modifier
                }
              )
              .padding(vertical = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Text(
            text =
              date.dayOfWeek
                .getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("fi-FI"))
                .replaceFirstChar { it.uppercase(Locale.forLanguageTag("fi-FI")) }
                .take(2),
            style = MaterialTheme.typography.labelSmall,
            color =
              if (isToday) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color =
              if (isToday) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurface,
          )
          // A fixed-height strip whether or not there are dots, so the day numbers stay on one
          // line across the week instead of jumping wherever a session happens to be.
          Row(
            modifier = Modifier.height(6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            sessions.take(3).forEach { session ->
              Box(
                modifier =
                  Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(workoutTypeColor(session.type))
              )
            }
          }
        }
      }
    }
  }
}

/** The colour a session's kind is drawn in, wherever it appears. */
internal fun workoutTypeColor(type: WorkoutType): Color =
  when (type) {
    WorkoutType.RUNNING -> ColorBlue
    WorkoutType.STRENGTH -> ColorGreen
    WorkoutType.SKIING -> ColorRed
  }

/**
 * Duration, movements and rounds as three equal columns.
 *
 * Only drawn for a session that has movements: "Liikkeet 0 · Kierrokset 1" on a run states two
 * facts that are true and useless, and the run's own line already carries its duration.
 */
@Composable
internal fun WorkoutStatColumns(
  durationMin: Int,
  movements: Int,
  rounds: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    WorkoutStat("Kesto", "$durationMin min", Modifier.weight(1f))
    WorkoutStat("Liikkeet", movements.toString(), Modifier.weight(1f))
    WorkoutStat("Kierrokset", rounds.toString(), Modifier.weight(1f))
  }
}

@Composable
private fun WorkoutStat(label: String, value: String, modifier: Modifier = Modifier) {
  Column(
    modifier =
      modifier
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
