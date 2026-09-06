package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import fi.merilainen.treenivalmentaja.domain.NextProgramGoal
import fi.merilainen.treenivalmentaja.domain.NextProgramProgression
import fi.merilainen.treenivalmentaja.domain.NextProgramRequest
import fi.merilainen.treenivalmentaja.domain.NextProgramState
import fi.merilainen.treenivalmentaja.domain.NextProgramSummary
import fi.merilainen.treenivalmentaja.domain.ProgramFit
import fi.merilainen.treenivalmentaja.domain.WorkoutType

/**
 * "Mitä seuraavaksi": the rating, the goal, the generated plan, and the decision to keep it.
 *
 * Offered only under a **final** report, because that is the only moment the question makes sense:
 * a programme that is still running does not need a successor, and a report the person has not read
 * is not a basis for one.
 *
 * The flow is deliberately three separate consents rather than one button. Asking for a programme
 * is not agreeing to one — the plan is generated, described by the app, and only written when the
 * person has seen what it contains.
 */
@Composable
fun NextProgramSection(
  state: NextProgramState?,
  onStart: () -> Unit,
  onGenerate: (NextProgramRequest) -> Unit,
  onImport: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (state) {
      null ->
        OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
          Text("Luo seuraava ohjelma")
        }

      NextProgramState.Choosing -> GoalPicker(onGenerate = onGenerate, onDismiss = onDismiss)

      is NextProgramState.Loading -> Busy("Luodaan ${state.request.weeks} viikon ohjelmaa…")

      NextProgramState.Importing -> Busy("Tallennetaan ohjelmaa…")

      is NextProgramState.Ready -> PlanPreview(state.summary, onImport = onImport, onDismiss = onDismiss)

      is NextProgramState.Invalid ->
        Problem(
          title = "Luotu ohjelma ei kelpaa",
          // The validator's own messages, verbatim. They name the field and the rule, which is
          // more use than "jokin meni pieleen" — and they are the same messages a hand-written
          // import would produce, so nothing new had to be invented for the model.
          lines = state.errors,
          retry = "Yritä uudelleen",
          onRetry = { onGenerate(state.request) },
          onDismiss = onDismiss,
        )

      is NextProgramState.Failed ->
        Problem(
          title = state.message,
          lines = emptyList(),
          retry = "Yritä uudelleen".takeIf { state.canRetry && state.request != null },
          onRetry = { state.request?.let(onGenerate) },
          onDismiss = onDismiss,
        )

      is NextProgramState.Imported ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              "Uusi ohjelma aloitettu",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
              "${state.planName} — ${state.sessions} harjoitusta. Kalenteri näyttää sen nyt.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onDismiss) { Text("Sulje") }
          }
        }
    }
  }
}

/**
 * The two questions asked before anything is generated.
 *
 * The rating comes first and is its own question, because it is the one piece of evidence the
 * measurements cannot supply: a programme can look successful in every figure and still have been
 * more than the person wanted to carry. Skipping it is allowed and is recorded as *absent* rather
 * than as "sopiva" — the same rule this app keeps about every other missing measurement.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalPicker(onGenerate: (NextProgramRequest) -> Unit, onDismiss: () -> Unit) {
  var fit by rememberSaveable { mutableStateOf<ProgramFit?>(null) }
  var goal by rememberSaveable { mutableStateOf(NextProgramGoal.BALANCED) }
  var ownGoal by rememberSaveable { mutableStateOf("") }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        "Miltä päättynyt ohjelma kokonaisuutena tuntui?",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProgramFit.entries.forEach { option ->
          FilterChip(
            selected = fit == option,
            // Tapping the chosen one again clears it: the honest answer to a question nobody wants
            // to answer is no answer, and there has to be a way back to it.
            onClick = { fit = if (fit == option) null else option },
            label = { Text(option.title) },
          )
        }
      }

      Text(
        "Haluatko painottaa seuraavassa ohjelmassa jotain erityisesti?",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NextProgramGoal.entries.forEach { option ->
          FilterChip(
            selected = goal == option,
            onClick = { goal = option },
            label = { Text(option.title) },
          )
        }
      }

      OutlinedTextField(
        value = ownGoal,
        onValueChange = { ownGoal = it },
        label = { Text("Oma tavoite tai tarkennus (valinnainen)") },
        singleLine = false,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            onGenerate(
              NextProgramRequest(
                goal = goal,
                ownGoal = ownGoal.trim().takeIf { it.isNotEmpty() },
                fit = fit,
              )
            )
          },
          modifier = Modifier.weight(1f),
        ) {
          Text("Luo ohjelma")
        }
        TextButton(onClick = onDismiss) { Text("Peruuta") }
      }
    }
  }
}

/**
 * What the plan contains, counted from the plan itself.
 *
 * Every figure here was computed by the app from the sessions the model wrote — see
 * `summariseProgramPlan`. The one thing quoted from the model is the plan's own description, which
 * is its statement of intent and belongs to it. Asking the model to summarise its own plan would
 * invite a description that flatters it; counting the sessions cannot.
 */
@Composable
private fun PlanPreview(summary: NextProgramSummary, onImport: () -> Unit, onDismiss: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        "Seuraavat ${summary.weeks} viikkoa",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      Text(
        summary.name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      summary.statedGoal?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
      }

      val lines = buildList {
        add("${summary.startDate} – ${summary.endDate}")
        add("${summary.sessions} harjoitusta, ${summary.sessionsPerWeek} viikossa")
        summary.byType.entries.sortedBy { it.key.ordinal }.forEach { (type, count) ->
          add("${type.title}: $count")
        }
        summary.runShape?.let { run ->
          if (run.weeklyKmFirstWeek != null && run.weeklyKmLastWeek != null) {
            add("Juoksua viikossa ${run.weeklyKmFirstWeek} km → ${run.weeklyKmLastWeek} km")
          }
          run.longestRunKm?.let { add("Pisin lenkki $it km") }
          if (run.byIntensity.isNotEmpty()) {
            add(
              "Juoksujen tehot: " +
                run.byIntensity.entries
                  .sortedBy { it.key.ordinal }
                  .joinToString(", ") { "${it.key.title} ${it.value}" }
            )
          }
        }
        summary.strengthShape?.let { strength ->
          add("Voimaharjoituksia ${strength.sessions}, ${strength.perWeek} viikossa")
          if (strength.recurringMovements.isNotEmpty()) {
            add("Toistuvat liikkeet: ${strength.recurringMovements.joinToString(", ")}")
          }
        }
        if (summary.progression != NextProgramProgression.UNKNOWN) {
          add("Juoksumäärän suunta: ${summary.progression.title}")
        }
      }
      lines.forEach {
        Text(
          "• $it",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
      }

      if (summary.changesFromPrevious.isNotEmpty()) {
        Text(
          "Muutokset edelliseen ohjelmaan",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        summary.changesFromPrevious.forEach {
          Text(
            "• $it",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
      }

      Text(
        "Sopiiko tämä suunnitelma? Uusi ohjelma korvaa nykyisen kalenterissa.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Aloita uusi ohjelma") }
        TextButton(onClick = onDismiss) { Text("Muokkaa tavoitteita") }
      }
    }
  }
}

@Composable
private fun Busy(text: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    CircularProgressIndicator(modifier = Modifier.size(20.dp))
    Text(
      text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Problem(
  title: String,
  lines: List<String>,
  retry: String?,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
      lines.take(MAX_ERRORS_SHOWN).forEach {
        Text(
          "• $it",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
      if (lines.size > MAX_ERRORS_SHOWN) {
        Text(
          "…ja ${lines.size - MAX_ERRORS_SHOWN} muuta virhettä",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (retry != null) TextButton(onClick = onRetry) { Text(retry) }
        TextButton(onClick = onDismiss) { Text("Sulje") }
      }
    }
  }
}

/**
 * A malformed plan can produce dozens of errors, and a card that grows to fill the calendar is not
 * more helpful than one that names the first few.
 */
private const val MAX_ERRORS_SHOWN = 6
