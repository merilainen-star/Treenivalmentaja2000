package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.EasyRunDrift
import fi.merilainen.treenivalmentaja.domain.WorkoutType

/**
 * A word before an easy session whose three predecessors were not easy.
 *
 * **It has no action button, and that is the design** rather than an omission — see
 * `EasyRunDriftUseCase`. The three sessions it reports on are already run, and lightening a session
 * that is meant to be light changes nothing worth changing. What is left is the fact itself,
 * delivered on the morning it can still make a difference.
 *
 * Every number the card asserts is on the card: the three measurements, the median they are being
 * compared with, and how many sessions that median was taken over. A finding a person cannot check
 * is the kind of advice this app removed a card for once already.
 */
@Composable
fun EasyRunDriftCard(finding: EasyRunDrift.Finding, onDismiss: () -> Unit = {}) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text =
          "${finding.type.easyPlural.replaceFirstChar { it.uppercase() }} ovat kiristyneet",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )

      Text(
        text =
          "Kolme viimeisintä ${finding.type.easyPartitive} meni kovempaa kuin " +
            "${finding.type.easyPlural} yleensä: " +
            "${finding.recentIntensityPercent.joinToString(" · ") { "$it %" }}. " +
            "${finding.comparableSessions} vertailukelpoisen ${finding.type.genitive} mediaani " +
            "on ${finding.medianIntensityPercent} %. " +
            "Tämän päivän ${finding.type.singular} on tarkoitettu kevyeksi.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )

      // The only button, and it changes nothing but this card. A note that cannot be put away
      // would be worse than one that comes back on the next easy morning the numbers still say it.
      TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Selvä") }
    }
  }
}

/** "kolme viimeisintä **kevyttä lenkkiä**" — what three of these are called. */
private val WorkoutType.easyPartitive: String
  get() =
    when (this) {
      WorkoutType.RUNNING -> "kevyttä lenkkiä"
      WorkoutType.SKIING -> "kevyttä hiihtolenkkiä"
      WorkoutType.STRENGTH -> "kevyttä lihaskuntotreeniä"
    }

/** "kovempaa kuin **kevyet lenkit** yleensä" — the baseline, in the plural. */
private val WorkoutType.easyPlural: String
  get() =
    when (this) {
      WorkoutType.RUNNING -> "kevyet lenkit"
      WorkoutType.SKIING -> "kevyet hiihtolenkit"
      WorkoutType.STRENGTH -> "kevyet lihaskuntotreenit"
    }

/** "11 vertailukelpoisen **lenkin** mediaani" — what the baseline was taken over. */
private val WorkoutType.genitive: String
  get() =
    when (this) {
      WorkoutType.RUNNING -> "lenkin"
      WorkoutType.SKIING -> "hiihtolenkin"
      WorkoutType.STRENGTH -> "treenin"
    }

/** "tämän päivän **lenkki**" — today's session, which is the one still ahead. */
private val WorkoutType.singular: String
  get() =
    when (this) {
      WorkoutType.RUNNING -> "lenkki"
      WorkoutType.SKIING -> "hiihtolenkki"
      WorkoutType.STRENGTH -> "treeni"
    }
