package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.ReadinessAdvice

/**
 * This morning's question about a poor readiness reading.
 *
 * It appears only when [ReadinessAdvice.Offer] says so, which needs a real measurement and a real
 * session — see `ReadinessAdviceUseCase`. That is the whole discipline of this card: the readiness
 * indicator this app used to have was removed for giving the same advice every day with nothing
 * behind it, and the difference here is that every word below is backed by a number and a plan.
 *
 * Both buttons do exactly what the app can already be asked to do by hand. Nothing new happens to
 * the plan because a card offered it — the offer only picks the moment.
 */
@Composable
fun ReadinessAdviceCard(
  offer: ReadinessAdvice.Offer,
  onShiftProgramme: () -> Unit = {},
  onStartLighter: () -> Unit = {},
  onDismiss: () -> Unit = {},
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text =
          when (offer.concern) {
            ReadinessAdvice.Concern.MISSED_AFTER_POOR_DAY -> "Eilinen jäi väliin"
            ReadinessAdvice.Concern.POOR_TODAY -> "Palautuminen on matala"
          },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )

      // The number is always named. A question about training that does not say what it is based
      // on is the kind of advice this card exists not to give.
      Text(
        text =
          when (offer.concern) {
            ReadinessAdvice.Concern.MISSED_AFTER_POOR_DAY ->
              "Eilen jäi treeni tekemättä ja palautuminen oli ${offer.readiness}. " +
                "Siirretäänkö ohjelmaa eteenpäin, vai aloitetaanko tämä päivä kevyemmin?"
            ReadinessAdvice.Concern.POOR_TODAY ->
              "Tämän aamun palautuminen on ${offer.readiness}. " +
                "Aloitetaanko tämän päivän treeni kevyemmin?"
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )

      HorizontalDivider()

      if (offer.shiftableSessionIds.isNotEmpty()) {
        Button(onClick = onShiftProgramme, modifier = Modifier.fillMaxWidth()) {
          Text("Siirrä ohjelmaa eteenpäin")
        }
      }
      if (offer.lightenableSessionIds.isNotEmpty()) {
        OutlinedButton(onClick = onStartLighter, modifier = Modifier.fillMaxWidth()) {
          Text("Aloita kevyemmin tänään")
        }
      }
      // Not "no": a question the app cannot be told to stop asking would be worse than one that
      // comes back tomorrow morning with a fresh reading behind it.
      TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Ei nyt") }
    }
  }
}
