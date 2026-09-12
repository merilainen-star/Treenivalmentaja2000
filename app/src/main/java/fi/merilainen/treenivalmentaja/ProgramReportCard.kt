package fi.merilainen.treenivalmentaja

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.NextProgramRequest
import fi.merilainen.treenivalmentaja.domain.NextProgramState
import fi.merilainen.treenivalmentaja.domain.ProgramReportBlock
import fi.merilainen.treenivalmentaja.domain.ProgramReportKind
import fi.merilainen.treenivalmentaja.domain.ProgramReportState
import fi.merilainen.treenivalmentaja.domain.programReportBlocks

/**
 * The whole-programme report: one button, and whatever it produced.
 *
 * Sits on the calendar rather than on a workout card, because it is about the plan and not about a
 * day. There is one of it on the screen, where `AiAnalysisSection` renders on every session in a
 * ten-day window — which is why this one may explain itself when it has nothing to offer and that
 * one may not. An explanation repeated across a whole training week is an advertisement; an
 * explanation on one card at the top of the calendar is an answer.
 *
 * Draws nothing when no API key is configured, on the same reasoning as the session analysis: a
 * feature that is switched off should be absent, not advertised.
 */
@Composable
fun ProgramReportCard(
  state: ProgramReportState?,
  configured: Boolean,
  onRequest: () -> Unit = {},
  onDismiss: () -> Unit = {},
  nextProgram: NextProgramState? = null,
  onStartNextProgram: () -> Unit = {},
  onGenerateNextProgram: (NextProgramRequest) -> Unit = {},
  onImportNextProgram: () -> Unit = {},
  onDismissNextProgram: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  if (!configured) return

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (state) {
      is ProgramReportState.Loading ->
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp))
          Text(
            text =
              when (state.kind) {
                ProgramReportKind.INTERIM -> "Kootaan väliraporttia…"
                ProgramReportKind.FINAL -> "Kootaan loppuraporttia…"
              },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

      is ProgramReportState.Loaded ->
        LoadedReport(
          state = state,
          onDismiss = onDismiss,
          nextProgram = nextProgram,
          onStartNextProgram = onStartNextProgram,
          onGenerateNextProgram = onGenerateNextProgram,
          onImportNextProgram = onImportNextProgram,
          onDismissNextProgram = onDismissNextProgram,
        )

      is ProgramReportState.Failed ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
              if (state.canRetry) TextButton(onClick = onRequest) { Text("Yritä uudelleen") }
              TextButton(onClick = onDismiss) { Text("Sulje") }
            }
          }
        }

      // Not a failure and not styled as one: a plan with two sessions behind it genuinely has no
      // report in it yet, and saying so is the correct answer to the question that was asked.
      is ProgramReportState.NotEnoughData ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("Sulje") }
          }
        }

      null ->
        OutlinedButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
          Text("Analysoi treeniohjelmani")
        }
    }
  }
}

/**
 * A finished report: a one-line card on the calendar, and the document itself on the whole screen.
 *
 * **It used to render inline, and that was wrong in a way only a phone shows.** The calendar lays
 * its children out in a Column, so a report of a few hundred words took the entire screen, could
 * not be scrolled — a Column does not scroll — and left the day rows measured at zero height. The
 * feature ate the screen it was a feature of. The owner found it the day it shipped: "tänä
 * analyysi sivu ei rullaa alaspäin".
 *
 * A sheet is the fix rather than a scrollbox in the card, because the content says so. This is a
 * document that was asked for deliberately, organised into fact, reading and advice; reading it in
 * a third of a screen while a calendar competes underneath serves neither. The sheet opens by
 * itself when the report arrives — that is the continuation of the tap that asked for it — and the
 * card stays behind so it can be reopened rather than re-requested. Re-requesting would spend
 * another call on a question already answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadedReport(
  state: ProgramReportState.Loaded,
  onDismiss: () -> Unit,
  nextProgram: NextProgramState?,
  onStartNextProgram: () -> Unit,
  onGenerateNextProgram: (NextProgramRequest) -> Unit,
  onImportNextProgram: () -> Unit,
  onDismissNextProgram: () -> Unit,
) {
  // Keyed on the report's own text, so a freshly generated one opens itself while a reopened card
  // does not fight the person who just closed it.
  var open by rememberSaveable(state.text) { mutableStateOf(true) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = state.kind.title(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      // The report's own first line, so the card says something about this report rather than
      // being a button that could belong to any of them.
      programReportBlocks(state.text)
        .filterIsInstance<ProgramReportBlock.Paragraph>()
        .firstOrNull()
        ?.let { first ->
          Text(
            text = first.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = true }) { Text("Lue raportti") }
        TextButton(onClick = onDismiss) { Text("Sulje") }
      }
    }
  }

  if (open) {
    ModalBottomSheet(
      onDismissRequest = { open = false },
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      ProgramReportSheetContent(
        state = state,
        nextProgram = nextProgram,
        onStartNextProgram = onStartNextProgram,
        onGenerateNextProgram = onGenerateNextProgram,
        onImportNextProgram = onImportNextProgram,
        onDismissNextProgram = onDismissNextProgram,
      )
    }
  }
}

/**
 * What the sheet holds: the report, and under a final one the offer to build the next programme.
 *
 * Stateless and separate from the sheet that hosts it, for the reason every `…Content` composable
 * in this app is: a `ModalBottomSheet` renders in its own window and a screenshot test cannot see
 * inside it, so the thing worth having a baseline of has to be capturable on its own.
 *
 * The scroll lives here rather than around the card, which is the whole point of the change — one
 * scrolling region, the height of the screen, with nothing underneath competing for it.
 */
@Composable
fun ProgramReportSheetContent(
  state: ProgramReportState.Loaded,
  nextProgram: NextProgramState? = null,
  onStartNextProgram: () -> Unit = {},
  onGenerateNextProgram: (NextProgramRequest) -> Unit = {},
  onImportNextProgram: () -> Unit = {},
  onDismissNextProgram: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ReportResult(state)
    // Offered under a final report only: a programme still running does not need a successor, and
    // the recommendation this flow acts on is written in that report's last section.
    if (state.kind == ProgramReportKind.FINAL) {
      NextProgramSection(
        state = nextProgram,
        onStart = onStartNextProgram,
        onGenerate = onGenerateNextProgram,
        onImport = onImportNextProgram,
        onDismiss = onDismissNextProgram,
      )
    }
    Spacer(modifier = Modifier.size(16.dp))
  }
}

private fun ProgramReportKind.title(): String =
  when (this) {
    ProgramReportKind.INTERIM -> "Väliraportti"
    ProgramReportKind.FINAL -> "Loppuraportti"
  }

/**
 * The report itself.
 *
 * Rendered as the model wrote it, headings and all. The session analysis forbids headings because
 * it is 110 words read at a glance; this is a document that was asked for deliberately, and its
 * structure — fact, then reading, then advice — is the point of the whole feature rather than
 * decoration to be flattened away.
 */
@Composable
private fun ReportResult(state: ProgramReportState.Loaded) {
  var showPrompt by rememberSaveable { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = state.kind.title(),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      // Headings drawn as headings. The model is asked for a fixed set of them and the separation
      // they create is the feature; printing them as literal "##" turns it into punctuation.
      programReportBlocks(state.text).forEach { block ->
        when (block) {
          is ProgramReportBlock.Heading ->
            Text(
              text = block.text,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          is ProgramReportBlock.Paragraph ->
            Text(
              text = block.text,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
      }

      TextButton(onClick = { showPrompt = !showPrompt }) {
        Text(if (showPrompt) "Piilota pyyntö" else "Näytä pyyntö")
      }

      AnimatedVisibility(visible = showPrompt) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { clipboard.setText(AnnotatedString(state.prompt)) }) {
            Text("Kopioi pyyntö leikepöydälle")
          }
          // Monospaced and side-scrolling, like the session analysis panel: this is the string
          // that was sent, and wrapping it would show something subtly different from the truth.
          Text(
            text = state.prompt,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
          )
        }
      }
    }
  }
}
