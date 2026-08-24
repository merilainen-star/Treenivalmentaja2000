package fi.merilainen.treenivalmentaja

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.AiAnalysisKind
import fi.merilainen.treenivalmentaja.domain.AiAnalysisState
import fi.merilainen.treenivalmentaja.domain.AiPlanProposalState

/**
 * The "AI-analyysi" button, and whatever it has produced.
 *
 * One composable for both screens rather than two that drift: `WorkoutCardToday` and the expanded
 * `WorkoutCardWeek` show the same session and must offer the same thing. It lives outside
 * `WorkoutDetails` because that function is deliberately read-only — content, never actions — and
 * the buttons on both cards sit beside it rather than inside it.
 *
 * Draws nothing at all in two cases, and both are deliberate:
 *
 *  - **[kind] is `null`** — the session is outside both windows. This is what keeps the button off
 *    the hundreds of rows the week list scrolls back through.
 *  - **[configured] is `false`** — no API key, so the feature is off. It draws *nothing*, not an
 *    explanation.
 *
 * That second one was a mistake first time round, and the screenshot suite is what caught it. The
 * original drew a line reading "AI-analyysi vaatii Anthropic API -avaimen" whenever a key was
 * missing, reasoning by analogy with the Oura card, which explains itself rather than offering a
 * button it knows cannot work. The analogy does not hold: the Oura card is **one** card on the
 * Settings screen, whereas this renders on *every* workout in a ten-day window. An owner who has no
 * interest in the AI feature — or has not set it up yet — would have found the same advertisement
 * repeated across their whole training week, on the two screens they actually use daily. Thirteen
 * screenshot baselines changed, which is exactly the right alarm for "you have altered every card
 * in the app".
 *
 * An opt-in feature that has not been opted into should be invisible. Settings is where you find out
 * it exists.
 *
 * @param kind which analysis this session can be asked for, from `AiAnalysisAvailability`.
 * @param state `null` before anything has been asked.
 * @param configured whether an API key exists. `false` draws nothing — see above.
 */
@Composable
fun AiAnalysisSection(
  kind: AiAnalysisKind?,
  state: AiAnalysisState?,
  configured: Boolean,
  onRequest: () -> Unit = {},
  onDismiss: () -> Unit = {},
  proposalState: AiPlanProposalState? = null,
  onRequestProposal: (String?) -> Unit = {},
  onApplyProposal: () -> Unit = {},
  onDismissProposal: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  if (kind == null || !configured) return

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when {
      state is AiAnalysisState.Loading ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp))
          Text(
            text = "Analysoidaan…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

      state is AiAnalysisState.Loaded -> AnalysisResult(state, onDismiss)

      state is AiAnalysisState.Failed -> AnalysisFailure(state, onRequest, onDismiss)

      else ->
        OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
          Text(
            when (kind) {
              AiAnalysisKind.COMPLETED -> "AI-analyysi: miten meni?"
              AiAnalysisKind.UPCOMING -> "AI-analyysi: miten tämä kannattaa tehdä?"
            }
          )
        }
    }

    if (kind == AiAnalysisKind.UPCOMING) {
      AiPlanAdvisorSection(
        state = proposalState,
        onRequest = onRequestProposal,
        onApply = onApplyProposal,
        onDismiss = onDismissProposal,
      )
    }
  }
}

/**
 * The answer, and the request behind it.
 *
 * The prompt is one tap away rather than hidden, because this is the one feature in the app that
 * sends health data off the device. `PRIVACY.md` and `SECURITY.md` both promise nothing invisible;
 * a panel showing the exact text that was sent is what that promise looks like on screen. It shows
 * the string that was actually sent, not a rebuild of it — the plan may have changed since.
 */
@Composable
private fun AnalysisResult(state: AiAnalysisState.Loaded, onDismiss: () -> Unit) {
  var showPrompt by rememberSaveable { mutableStateOf(false) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = state.text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { showPrompt = !showPrompt }) {
          Text(if (showPrompt) "Piilota pyyntö" else "Näytä pyyntö")
        }
        TextButton(onClick = onDismiss) { Text("Sulje") }
      }

      AnimatedVisibility(visible = showPrompt) {
        Text(
          text = state.prompt,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          // The prompt is pre-formatted text with lines that do not wrap sensibly; scrolling it
          // sideways beats reflowing it into something that no longer matches what was sent.
          modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
      }
    }
  }
}

/** A failure, and a retry only when waiting could actually help. */
@Composable
private fun AnalysisFailure(
  state: AiAnalysisState.Failed,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = state.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Offered only when the failure says waiting would help. A wrong key and a missing model
        // will fail identically forever, and a retry button beside them is a lie about the remedy.
        if (state.canRetry) {
          TextButton(onClick = onRetry) { Text("Yritä uudelleen") }
        }
        TextButton(onClick = onDismiss) { Text("Sulje") }
      }
    }
  }
}
