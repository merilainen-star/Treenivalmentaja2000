package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fi.merilainen.treenivalmentaja.domain.IntervalsActivityRef
import fi.merilainen.treenivalmentaja.domain.IntervalsRawResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * intervals.icu's answers, exactly as they arrive.
 *
 * A diagnostics tool, not a way to read training. It exists because a number on a session card can
 * disagree with the watch — a duration, a pace — and the only way to settle that is to look at what
 * the service actually sent rather than at what this app made of it.
 *
 * Two rules it keeps, and both matter more than how it looks:
 *
 * **What is shown is the response body.** Not a DTO serialised again: the bytes are kept as text
 * from the socket to the screen, and only whitespace is inserted for readability. A field this app
 * does not know about is therefore visible here, which is the entire point.
 *
 * **The API key never appears.** The request line shows path and query, both of which cannot carry
 * the credential — it travels in an `Authorization` header that is attached inside the client and
 * recorded nowhere. What the copy button puts on the clipboard is the JSON body and nothing else,
 * which matters here more than anywhere: this is the one screen whose contents someone is likely
 * to paste somewhere public.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntervalsRawDataSheet(
  response: IntervalsRawResponse?,
  loading: Boolean,
  error: String?,
  activities: List<IntervalsActivityRef>,
  onFetchActivities: () -> Unit,
  onFetchActivity: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Column(
      modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Intervals.icu — raakadata",
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        text =
          "Kehitystyökalu. Näyttää vastauksen sellaisena kuin palvelin sen lähetti, ilman " +
            "kenttärajausta — myös ne kentät joita sovellus ei käytä.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onFetchActivities, enabled = !loading) {
          Text(if (response == null) "Hae viikon treenit" else "Päivitä")
        }
        if (loading) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      }

      // Offered only once a list has been fetched, because the ids come from what was stored.
      if (activities.isNotEmpty()) {
        Text(
          text = "Tai yksi harjoitus kokonaan:",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        activities.take(MAX_PICKER_ROWS).forEach { activity ->
          OutlinedButton(
            onClick = { onFetchActivity(activity.id) },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(activity.label(), style = MaterialTheme.typography.bodySmall)
          }
        }
      }

      error?.let {
        Text(
          text = it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }

      response?.let { ResponseBody(it) }
    }
  }
}

/**
 * The metadata line, the copy button, and the body.
 *
 * The metadata is deliberately *outside* what the copy button takes: someone pasting this into an
 * editor to search it wants JSON that parses, not JSON with four lines of preamble on top.
 */
@Composable
private fun ColumnScope.ResponseBody(response: IntervalsRawResponse) {
  val clipboard = LocalClipboardManager.current
  var copied by remember(response) { mutableStateOf(false) }

  // The pretty-printed text is computed once per response rather than on every recomposition; a
  // week of unfiltered activities is a large string and this runs on the main thread.
  val pretty = remember(response) { response.prettyBody }
  val lines = remember(pretty) { pretty.lines() }

  HorizontalDivider()

  Text(
    text = response.endpoint,
    style = MaterialTheme.typography.bodySmall,
    fontFamily = FontFamily.Monospace,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Text(
    text =
      "HTTP ${response.status} · ${response.byteSize.asKilobytes()} · " +
        "haettu ${response.fetchedAtUtc.asLocalTime()}",
    style = MaterialTheme.typography.bodySmall,
    color =
      if (response.isSuccess) MaterialTheme.colorScheme.onSurfaceVariant
      else MaterialTheme.colorScheme.error,
  )

  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      onClick = {
        // The body only. Never the request line, and never anything derived from the key.
        clipboard.setText(AnnotatedString(pretty))
        copied = true
      }
    ) {
      Text("Kopioi JSON leikepöydälle")
    }
  }
  if (copied) {
    Text(
      text = "JSON kopioitu leikepöydälle",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary,
    )
    LaunchedEffect(response) {
      delay(CONFIRMATION_MILLIS)
      copied = false
    }
  }

  if (response.body.isBlank()) {
    Text(
      text = "Palvelin ei palauttanut runkoa.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }

  HorizontalDivider()

  // One item per line rather than one enormous Text.
  //
  // A week of unfiltered activities runs to hundreds of kilobytes, and a single Text of that
  // measures and lays out every glyph at once — which is how a diagnostics screen ends up being
  // the thing that crashes. A LazyColumn draws only the lines on screen, so the response can be
  // any size. Horizontal scrolling is per line, because a deeply nested value should not wrap into
  // something that no longer looks like JSON.
  LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
    items(lines.size) { index ->
      Text(
        text = lines[index],
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier.horizontalScroll(rememberScrollState()),
      )
    }
  }
}

/** `15.8.2026 12:04 · Run · 9,52 km` — enough to tell one row from the next. */
private fun IntervalsActivityRef.label(): String {
  val finnish = Locale("fi", "FI")
  val when_ =
    Instant.ofEpochMilli(startTimeUtc)
      .atZone(ZoneId.systemDefault())
      .format(DateTimeFormatter.ofPattern("d.M.yyyy HH:mm", finnish))
  val distance = distanceMeters?.let { String.format(finnish, " · %.2f km", it / 1000.0) }.orEmpty()
  return "$when_ · $sportType$distance"
}

private fun Int.asKilobytes(): String =
  String.format(Locale("fi", "FI"), "%.1f kB", this / 1024.0)

private fun Long.asLocalTime(): String =
  Instant.ofEpochMilli(this)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("d.M.yyyy HH:mm:ss", Locale("fi", "FI")))

/** Enough to reach the run being investigated without turning the sheet into a list screen. */
private const val MAX_PICKER_ROWS = 12

private const val CONFIRMATION_MILLIS = 2_500L
