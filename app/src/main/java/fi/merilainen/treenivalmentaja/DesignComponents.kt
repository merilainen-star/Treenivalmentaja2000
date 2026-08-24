package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
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

/** A real Gregorian month grid. Mockup coordinates are never used as calendar data. */
@Composable
internal fun MonthCalendar(
  today: LocalDate,
  workouts: List<Workout>,
  modifier: Modifier = Modifier,
) {
  var monthText by rememberSaveable(today) { mutableStateOf(YearMonth.from(today).toString()) }
  val month = runCatching { YearMonth.parse(monthText) }.getOrDefault(YearMonth.from(today))
  val sessionsByDate =
    workouts.groupBy { workout -> today.plusDays(workout.dayOffset.toLong()) }
  val first = month.atDay(1)
  val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
  val cells = List(42) { index ->
    val day = index - leading + 1
    day.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
  }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Edellinen kuukausi")
      }
      Text(
        text =
          month.month
            .getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("fi-FI"))
            .replaceFirstChar { it.uppercase(Locale.forLanguageTag("fi-FI")) } + " ${month.year}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      IconButton(onClick = { monthText = month.plusMonths(1).toString() }) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Seuraava kuukausi")
      }
    }

    Row(modifier = Modifier.fillMaxWidth()) {
      listOf("Ma", "Ti", "Ke", "To", "Pe", "La", "Su").forEach { day ->
        Text(
          text = day,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    cells.chunked(7).forEach { week ->
      Row(modifier = Modifier.fillMaxWidth()) {
        week.forEach { date ->
          val sessions = date?.let(sessionsByDate::get).orEmpty()
          val completed = sessions.isNotEmpty() && sessions.all { it.status == SessionStatus.COMPLETED }
          val isToday = date == today
          val container =
            when {
              isToday -> MaterialTheme.colorScheme.primaryContainer
              completed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
              sessions.isNotEmpty() -> MaterialTheme.colorScheme.surfaceContainerHigh
              else -> Color.Transparent
            }
          Box(
            modifier =
              Modifier
                .weight(1f)
                .aspectRatio(1f)
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(container),
            contentAlignment = Alignment.Center,
          ) {
            if (date != null) {
              Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isToday || completed) FontWeight.Bold else FontWeight.Normal,
                color =
                  if (isToday) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurface,
              )
              if (sessions.isNotEmpty()) {
                Box(
                  modifier =
                    Modifier
                      .align(Alignment.BottomCenter)
                      .padding(bottom = 3.dp)
                      .size(4.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.primary)
                )
              }
            }
          }
        }
      }
    }
  }
}
