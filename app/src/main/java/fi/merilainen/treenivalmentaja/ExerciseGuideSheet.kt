package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState

/**
 * What a movement looks like: an animation, a few lines of instruction, and the way out.
 *
 * A memory aid, not an exercise library. Everything it shows is fetched when the sheet opens and
 * kept only in memory — see `docs/EXERCISE_GUIDE.md`. The session behind it stays fully usable in
 * every state, including all the ones where nothing could be fetched at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseGuideSheet(
    state: ExerciseGuideState,
    onRetry: () -> Unit,
    onSelectSuggestion: (ExerciseGuide) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ExerciseGuideSheetContent(
            state = state,
            onRetry = onRetry,
            onSelectSuggestion = onSelectSuggestion,
        )
    }
}

/**
 * The sheet's body, stateless so every one of its states can be rendered by a test.
 *
 * @param animation how to draw the movement's image. A parameter because the real one is a
 *   network GIF: it can never appear in a screenshot baseline, and leaving Coil to race the
 *   capture would make the baselines depend on how fast a request failed.
 */
@Composable
fun ExerciseGuideSheetContent(
    state: ExerciseGuideState,
    onRetry: () -> Unit,
    onSelectSuggestion: (ExerciseGuide) -> Unit,
    modifier: Modifier = Modifier,
    animation: @Composable (url: String) -> Unit = { GuideAnimation(it) },
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = state.exerciseName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        when (state) {
            is ExerciseGuideState.Loading -> LoadingBody()
            is ExerciseGuideState.Loaded -> LoadedBody(state, animation)
            is ExerciseGuideState.Suggestions -> SuggestionsBody(state, onSelectSuggestion)
            is ExerciseGuideState.Unavailable -> UnavailableBody(state, onRetry)
        }

        // Credit belongs to whoever's data is on screen, so it is carried by the guide rather
        // than by the sheet: one source credits itself, the other names each image's author. The
        // states that show no data show no credit either — there is nothing to attribute.
        val credits =
            when (state) {
                is ExerciseGuideState.Loaded -> listOf(state.guide.attribution)
                is ExerciseGuideState.Suggestions -> state.matches.map { it.attribution }.distinct()
                else -> emptyList()
            }
        if (credits.isNotEmpty()) {
            HorizontalDivider()
            credits.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text = "Haetaan liiketietoja…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadedBody(
    state: ExerciseGuideState.Loaded,
    animation: @Composable (url: String) -> Unit,
) {
    val guide = state.guide

    if (state.suggested) {
        // The plan never said this is the same movement. The sheet must not start pretending it
        // did just because the name search returned something.
        Text(
            text = "Ehdotus — tarkista että liike on sama.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        text = guide.name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )

    if (guide.imageUrl.isNotBlank()) {
        animation(guide.imageUrl)
    }

    guide.instructions.forEachIndexed { index, line ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = line, style = MaterialTheme.typography.bodyMedium)
        }
    }

    val secondary = buildList {
        if (guide.targetMuscles.isNotEmpty()) add("Kohde: ${guide.targetMuscles.joinToString(", ")}")
        if (guide.equipment.isNotEmpty()) add("Välineet: ${guide.equipment.joinToString(", ")}")
    }
    secondary.forEach {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Several plausible matches for a name the plan did not pin down.
 *
 * Names only, no thumbnails: five images would be five requests against a service whose only
 * published rate limit is the word "strict", and four of them would be for movements the user is
 * about to not pick.
 */
@Composable
private fun SuggestionsBody(
    state: ExerciseGuideState.Suggestions,
    onSelectSuggestion: (ExerciseGuide) -> Unit,
) {
    Text(text = "Tarkoititko:", style = MaterialTheme.typography.bodyMedium)

    state.matches.forEach { match ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectSuggestion(match) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = match.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Text(
        text = "Nämä ovat arvauksia liikkeen nimestä. Varma tapa on lisätä guide-viite " +
            "suunnitelmaan.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UnavailableBody(state: ExerciseGuideState.Unavailable, onRetry: () -> Unit) {
    Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
    if (state.canRetry) {
        Button(onClick = onRetry) { Text("Yritä uudelleen") }
    }
}

/**
 * The movement's animation, at a fixed height so the sheet does not jump as it arrives.
 *
 * A failure here keeps the instructions on screen and leaves a quiet box behind — the guide is
 * an extra, and half of it is better than an empty sheet.
 */
@Composable
fun GuideAnimation(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(200.dp),
            loading = { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) },
            error = {
                Text(
                    text = "Kuvaa ei saatu ladattua.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
