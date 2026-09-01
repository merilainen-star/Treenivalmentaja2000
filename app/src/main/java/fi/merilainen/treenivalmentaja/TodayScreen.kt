package fi.merilainen.treenivalmentaja

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.key
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.domain.AiAnalysisAvailability
import fi.merilainen.treenivalmentaja.domain.AiAnalysisKind
import fi.merilainen.treenivalmentaja.domain.AiAnalysisState
import fi.merilainen.treenivalmentaja.domain.AiPlanProposalState
import fi.merilainen.treenivalmentaja.domain.CompletedSessionMetrics
import fi.merilainen.treenivalmentaja.domain.DailyRecovery
import fi.merilainen.treenivalmentaja.domain.EasyRunDrift
import fi.merilainen.treenivalmentaja.domain.ReadinessAdvice
import fi.merilainen.treenivalmentaja.domain.MissedSessionsProposal
import fi.merilainen.treenivalmentaja.domain.CompletedRunMetrics
import fi.merilainen.treenivalmentaja.domain.formatDuration
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.ui.theme.ColorBlue
import fi.merilainen.treenivalmentaja.ui.theme.ColorGray
import fi.merilainen.treenivalmentaja.ui.theme.ColorGreen
import fi.merilainen.treenivalmentaja.ui.theme.ColorRed
import fi.merilainen.treenivalmentaja.ui.theme.ColorYellow

/**
 * The stateful wrapper: reads the ViewModel and hands plain values down.
 *
 * Everything below this function takes what it needs as parameters, which is what lets a test
 * render the whole screen. It used to take the ViewModel all the way down, so screenshot cover
 * stopped at the individual cards and nothing ever verified how they sit together.
 */
@Composable
fun TodayScreen(
    viewModel: WorkoutViewModel,
    onOpenActiveWorkout: (String) -> Unit = {},
) {
    val workouts by viewModel.workouts.collectAsState()
    val guideState by viewModel.guideState.collectAsState()
    val ouraState by viewModel.ouraState.collectAsState()
    val recovery by viewModel.todayRecovery.collectAsState()
    val syncing by viewModel.ouraSyncing.collectAsState()
    val syncFailure by viewModel.lastSyncFailure.collectAsState()
    val completedMetrics by viewModel.completedMetrics.collectAsState()
    val unmatched by viewModel.unmatchedToday.collectAsState()
    val intervalsState by viewModel.intervalsState.collectAsState()
    val runMetrics by viewModel.runMetrics.collectAsState()
    val advice by viewModel.readinessAdvice.collectAsState()
    val drift by viewModel.easyRunDrift.collectAsState()
    val analyses by viewModel.aiAnalyses.collectAsState()
    val planProposals by viewModel.aiPlanProposals.collectAsState()
    val analysisConfigured by viewModel.analysisConfigured.collectAsState()
    val analysisModel by viewModel.analysisModel.collectAsState()
    val missedProposal by viewModel.missedSessionsProposal.collectAsState()

    // On every **resume**, not merely on first composition.
    //
    // A LaunchedEffect here only ran when this screen entered composition, so an app left open in
    // the background since morning never fetched again: the workout recorded at 07:38 was not there
    // when the screen was composed, and nothing asked afterwards. Coming back to the app is exactly
    // when the answer is likely to have changed. A disconnected Oura makes it a no-op.
    LifecycleResumeEffect(ouraState, intervalsState) {
        viewModel.refreshCurrentDate()
        viewModel.checkMissedSessions()
        viewModel.syncOura()
        viewModel.syncIntervals()
        onPauseOrDispose {}
    }

    TodayScreenContent(
        workouts = workouts,
        guideState = guideState,
        onSickClicked = viewModel::markSick,
        onRecoveredClicked = viewModel::markRecovered,
        onStatusChange = viewModel::updateWorkoutStatus,
        onGuidedProgressChange = viewModel::recordGuidedProgress,
        onOpenActiveWorkout = onOpenActiveWorkout,
        onMoveToTomorrow = viewModel::moveWorkoutToTomorrow,
        onExerciseClick = viewModel::openExerciseGuide,
        onGuideRetry = viewModel::retryExerciseGuide,
        onGuideSuggestionSelected = viewModel::selectGuideSuggestion,
        onGuideDismiss = viewModel::closeExerciseGuide,
        recovery = recovery,
        ouraConnected = ouraState == OuraConnectionState.Connected,
        syncing = syncing,
        syncFailure = syncFailure,
        completedMetrics = completedMetrics,
        unmatchedWorkouts = unmatched,
        runMetrics = runMetrics,
        readinessAdvice = advice,
        onShiftProgramme = viewModel::shiftProgrammeForward,
        onStartLighter = viewModel::startTodayLighter,
        onDismissAdvice = viewModel::dismissReadinessAdvice,
        easyRunDrift = drift,
        onDismissDrift = viewModel::dismissEasyRunDrift,
        missedSessionsProposal = missedProposal,
        onAcceptMissedSessions = viewModel::acceptMissedSessionsProposal,
        onRejectMissedSessions = viewModel::rejectMissedSessionsProposal,
        onCompleteMissedSessions = viewModel::completeMissedSessionsProposal,
        analyses = analyses,
        planProposals = planProposals,
        analysisConfigured = analysisModel.provider in analysisConfigured,
        onRequestAnalysis = viewModel::requestAiAnalysis,
        onDismissAnalysis = viewModel::dismissAiAnalysis,
        onRequestPlanProposal = viewModel::requestAiPlanProposal,
        onApplyPlanProposal = viewModel::applyAiPlanProposal,
        onDismissPlanProposal = viewModel::dismissAiPlanProposal,
    )
}

/** Today, as a function of what it is given. Every callback defaults to nothing so a capture of
 *  one state does not have to spell out eight of them. */
@Composable
fun TodayScreenContent(
    workouts: List<Workout>,
    guideState: ExerciseGuideState? = null,
    onSickClicked: () -> Unit = {},
    onRecoveredClicked: () -> Unit = {},
    onStatusChange: (String, SessionStatus) -> Unit = { _, _ -> },
    onGuidedProgressChange: (String, GuidedProgress) -> Unit = { _, _ -> },
    onOpenActiveWorkout: (String) -> Unit = {},
    onMoveToTomorrow: (String) -> Unit = {},
    onExerciseClick: (Exercise) -> Unit = {},
    onGuideRetry: () -> Unit = {},
    onGuideSuggestionSelected: (ExerciseGuide) -> Unit = {},
    onGuideDismiss: () -> Unit = {},
    recovery: DailyRecovery? = null,
    ouraConnected: Boolean = false,
    syncing: Boolean = false,
    syncFailure: String? = null,
    completedMetrics: Map<String, CompletedSessionMetrics> = emptyMap(),
    unmatchedWorkouts: List<CompletedSessionMetrics> = emptyList(),
    runMetrics: Map<String, CompletedRunMetrics> = emptyMap(),
    readinessAdvice: ReadinessAdvice = ReadinessAdvice.None,
    onShiftProgramme: () -> Unit = {},
    onStartLighter: () -> Unit = {},
    onDismissAdvice: () -> Unit = {},
    easyRunDrift: EasyRunDrift = EasyRunDrift.None,
    onDismissDrift: () -> Unit = {},
    missedSessionsProposal: MissedSessionsProposal = MissedSessionsProposal.None,
    onAcceptMissedSessions: () -> Unit = {},
    onRejectMissedSessions: () -> Unit = {},
    onCompleteMissedSessions: () -> Unit = {},
    analyses: Map<String, AiAnalysisState> = emptyMap(),
    planProposals: Map<String, AiPlanProposalState> = emptyMap(),
    analysisConfigured: Boolean = false,
    onRequestAnalysis: (String) -> Unit = {},
    onDismissAnalysis: (String) -> Unit = {},
    onRequestPlanProposal: (String, String?) -> Unit = { _, _ -> },
    onApplyPlanProposal: (String) -> Unit = {},
    onDismissPlanProposal: (String) -> Unit = {},
) {
    val todayWorkouts = workouts.filter { it.dayOffset == 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Tänään",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        RecoveryCard(
            onSickClicked = onSickClicked,
            onRecoveredClicked = onRecoveredClicked,
            recovery = recovery,
            ouraConnected = ouraConnected,
            syncing = syncing,
            syncFailure = syncFailure,
        )

        if (missedSessionsProposal != MissedSessionsProposal.None) {
            MissedSessionsProposalCard(
                proposal = missedSessionsProposal,
                onAccept = onAcceptMissedSessions,
                onReject = onRejectMissedSessions,
                onComplete = onCompleteMissedSessions,
            )
        }

        // Directly under the reading it is about, and above the day's sessions — it asks what to
        // do with them, so it has to be read first.
        (readinessAdvice as? ReadinessAdvice.Offer)?.let { offer ->
            ReadinessAdviceCard(
                offer = offer,
                onShiftProgramme = onShiftProgramme,
                onStartLighter = onStartLighter,
                onDismiss = onDismissAdvice,
            )
        }

        // After the readiness question and before the sessions, in the order a morning is read:
        // what the body says, what to do about it, then what the last three easy ones actually
        // were. It asks for nothing, so it sits below the card that does.
        (easyRunDrift as? EasyRunDrift.Finding)?.let { finding ->
            EasyRunDriftCard(finding = finding, onDismiss = onDismissDrift)
        }

        if (todayWorkouts.isNotEmpty()) {
            todayWorkouts.forEach { workout ->
                WorkoutCardToday(
                    workout = workout,
                    onStatusChange = { newStatus -> onStatusChange(workout.id, newStatus) },
                    onGuidedProgressChange = { progress ->
                        onGuidedProgressChange(workout.id, progress)
                    },
                    onOpenActiveWorkout = { onOpenActiveWorkout(workout.id) },
                    onMoveToTomorrow = { onMoveToTomorrow(workout.id) },
                    onExerciseClick = onExerciseClick,
                    completed = completedMetrics[workout.id],
                    run = runMetrics[workout.id],
                    analysis = analyses[workout.id],
                    planProposal = planProposals[workout.id],
                    analysisConfigured = analysisConfigured,
                    onRequestAnalysis = { onRequestAnalysis(workout.id) },
                    onDismissAnalysis = { onDismissAnalysis(workout.id) },
                    onRequestPlanProposal = { answer -> onRequestPlanProposal(workout.id, answer) },
                    onApplyPlanProposal = { onApplyPlanProposal(workout.id) },
                    onDismissPlanProposal = { onDismissPlanProposal(workout.id) },
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Ei treeniä tälle päivälle. Nauti lepopäivästä!",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // What Oura recorded that no session claims. Listed rather than dropped: otherwise a
        // workout the matcher could not place is indistinguishable from one never fetched.
        if (unmatchedWorkouts.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Muu Ourassa kirjattu liikunta",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    unmatchedWorkouts.forEach { metrics ->
                        CompletedMetricsRow(metrics)
                    }
                }
            }
        }

        // Renders in its own window, so it costs the scrolling column no layout at all.
        guideState?.let { state ->
            ExerciseGuideSheet(
                state = state,
                onRetry = onGuideRetry,
                onSelectSuggestion = onGuideSuggestionSelected,
                onDismiss = onGuideDismiss,
            )
        }
    }
}

/**
 * A calendar change shown before it is made; rejecting this card is a pure no-op.
 *
 * Three answers, because two were not enough. Shifting the programme forward assumes the sessions
 * are still to be done, and rejecting changes nothing — so a backlog that will never be trained
 * came back as the same card every day. "Merkitse tehdyiksi" closes those sessions where they
 * stand, which is the only one of the three that ends the question.
 *
 * The two writing actions are laid out one per row rather than three across: "Merkitse tehdyiksi"
 * closes sessions for good, and a button that does that should not be a narrow one squeezed in
 * beside the others on a phone.
 */
@Composable
private fun MissedSessionsProposalCard(
    proposal: MissedSessionsProposal,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onComplete: () -> Unit,
) {
    val missedCount = when (proposal) {
        is MissedSessionsProposal.MoveOne -> 1
        is MissedSessionsProposal.ShiftPlan -> proposal.missedSessionIds.size
        MissedSessionsProposal.None -> return
    }
    val preview = when (proposal) {
        is MissedSessionsProposal.MoveOne ->
            "Yksi harjoitus jäi väliin ${proposal.fromDate}. Se siirretään seuraavalle " +
                "vapaalle päivälle ${proposal.toDate}."
        is MissedSessionsProposal.ShiftPlan ->
            "${proposal.missedSessionIds.size} harjoitusta jäi väliin. Koko avoin ohjelma " +
                "(${proposal.affectedSessions} harjoitusta) siirretään ${proposal.days} päivää eteenpäin."
        MissedSessionsProposal.None -> return
    }
    val completeLabel =
        if (missedCount == 1) "Merkitse tehdyksi" else "Merkitse $missedCount tehdyiksi"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Väliin jääneet harjoitukset", fontWeight = FontWeight.Bold)
            Text(preview, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Kalenteria muutetaan vasta, jos hyväksyt ehdotuksen. " +
                    "Jos harjoitukset ovat vanhoja etkä aio tehdä niitä, merkitse ne tehdyiksi — " +
                    "silloin ne poistuvat listalta eikä tätä kysytä uudelleen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Hyväksy siirto") }
                OutlinedButton(onClick = onReject) { Text("Hylkää") }
            }
            OutlinedButton(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                Text(completeLabel)
            }
        }
    }
}

/**
 * Today's recovery, and the two things the app can be told about your condition.
 *
 * There used to be an indicator here reading "Palautuminen: Kohtalainen" above the advice
 * "Kevyempi versio voi olla järkevä". Nothing fed either of them — the value was a constant set in
 * two places, both to the same thing — so the app repeated one verdict every day and nudged towards
 * a lighter session on all of them: advice with nothing behind it, wearing the clothes of a
 * measurement. It was removed, and this is it coming back with a number underneath.
 *
 * Four states, and telling them apart is the entire design:
 * - **Oura not connected** — no indicator at all, exactly as before. Silence beats invention.
 * - **Nothing fetched yet** — says so, rather than showing an empty reading as if it were one.
 * - **A day with no score** — the ring was not worn, or the night is not processed. Oura answers
 *   with a document whose `score` is `null`, so this says "ei tietoa" about a day that exists. It
 *   must never be drawn as a zero, which would read as "you are wrecked".
 * - **A reading** — the number, and a word for it. The word describes the score, never what to do
 *   about it; a training instruction with a measurement behind it is a later decision, and one
 *   without a measurement is what this card was stripped for.
 */
@Composable
fun RecoveryCard(
    onSickClicked: () -> Unit,
    onRecoveredClicked: () -> Unit,
    recovery: DailyRecovery? = null,
    ouraConnected: Boolean = false,
    syncing: Boolean = false,
    syncFailure: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (ouraConnected) {
                RecoveryReading(recovery = recovery, syncing = syncing, syncFailure = syncFailure)
                HorizontalDivider()
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Both tonal rather than one outlined and one filled: neither is the day's main
                // action, and a filled button next to an outlined one reads as a recommendation.
                // "Sairastuin" keeps the error colour, because that is what it means.
                FilledTonalButton(
                    onClick = onSickClicked,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                ) {
                    Text("Sairastuin")
                }
                FilledTonalButton(
                    onClick = onRecoveredClicked,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tervehdyin")
                }
            }
        }
    }
}

/**
 * The reading itself.
 *
 * Sleep and activity are shown beside readiness only when they exist, because a row of dashes is
 * noise. A failed sync is a footnote rather than a dialog: the number above it is what the database
 * holds, and it stays true whether or not the last fetch reached Oura.
 */
@Composable
private fun RecoveryReading(recovery: DailyRecovery?, syncing: Boolean, syncFailure: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Päivän yhteenveto",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        when {
            recovery != null && !recovery.isEmpty ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MetricRing("Palautuminen", recovery.readiness, Modifier.weight(1f))
                    MetricRing("Uni", recovery.sleep, Modifier.weight(1f))
                    MetricRing("Aktiivisuus", recovery.activity, Modifier.weight(1f))
                }
            recovery != null ->
                Text(
                    // The ring was not worn, or the night has not been scored yet. Saying so beats
                    // a zero, which would read as a verdict.
                    text = "Ei tietoa tältä päivältä",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            syncing ->
                Text(
                    text = "Haetaan Ourasta…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                Text(
                    text = "Ei vielä haettu",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        recovery?.readinessLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        syncFailure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * What Oura recorded for a session that was done, under the session it belongs to.
 *
 * Only the measurements that exist are drawn. A strength session has no distance, a ring that was
 * charging has no heart rate, and a row of dashes standing in for them would be worse than their
 * absence — the plan already says what was asked for, and this line is only here to say what
 * actually happened.
 */
@Composable
fun CompletedMetricsRow(
    metrics: CompletedSessionMetrics,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val parts = buildList {
        add("${metrics.durationMin} min")
        metrics.distanceKm?.let { add(String.format(Locale("fi", "FI"), "%.1f km", it)) }
        metrics.calories?.let { add("$it kcal") }
        metrics.avgHeartRate?.let { avg ->
            val max = metrics.maxHeartRate
            add(if (max != null) "syke $avg (max $max)" else "syke $avg")
        }
    }
    Text(
        text = parts.joinToString(" · "),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the watch recorded for a session, under the session it belongs to.
 *
 * Labelled "Kello" because Oura's line sits right above it and the two describe the same session
 * from different devices — without the label, two duration figures a minute apart read as a bug.
 * The label names the *device*, not the service the data travelled through: the reader cares that
 * this is the watch's own recording, and intervals.icu is plumbing they should not have to think
 * about while reading a training log.
 *
 * **Three lines rather than one.** There are up to nine numbers here, and a single `·`-joined
 * string of them stops being read at about the fourth — the eye has nothing to group by. They are
 * therefore split by the question each answers: how the running went, what the body did, and what
 * it cost. Pace leads the first line because it is the measurement this integration exists for and
 * the one Oura cannot supply.
 *
 * @param compact one line instead of three, for the week list's collapsed header. Scanning a
 *   fortnight for how the running went wants pace and distance and nothing else; the rest is there
 *   on the day's own card.
 */
@Composable
fun RunMetricsRow(
    metrics: CompletedRunMetrics,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    compact: Boolean = false,
) {
    val finnish = Locale("fi", "FI")
    val distance = metrics.distanceKm?.let { String.format(finnish, "%.2f km", it) }

    // How fast, and how far. The watch's own pace leads.
    val effort = buildList {
        metrics.paceText?.let { pace ->
            val max = metrics.maxPaceText
            add(if (max != null && !compact) "$pace (max $max)" else pace)
        }
        distance?.let { add(it) }
        if (compact) add(metrics.primaryDurationSec.formatDuration())
        if (!compact) metrics.elevationGainMeters?.let { add("nousu $it m") }
    }
    // The three durations, side by side. They differ for real reasons — the watch counts moving
    // time one way and intervals.icu recomputes it another — and showing one alone is what made
    // the app disagree with the wrist for no visible reason.
    val durations = buildList {
        metrics.activeDurationSec?.let { add("aktiivinen ${it.formatDuration()}") }
        add("liikkeessä ${metrics.movingTimeSec.formatDuration()}")
        metrics.recordingTimeSec?.let { add("yhteensä ${it.formatDuration()}") }
    }
    // What the body was doing while it happened.
    val body = buildList {
        metrics.avgHeartRate?.let { avg ->
            val max = metrics.maxHeartRate
            add(if (max != null) "syke $avg (max $max)" else "syke $avg")
        }
        metrics.stepsPerMinute?.let { add("askeltiheys $it") }
    }
    // What it cost — the numbers that make a hard 5 km distinguishable from an easy one.
    val cost = buildList {
        metrics.calories?.let { add("$it kcal") }
        metrics.trainingLoad?.let { add("kuormitus $it") }
        metrics.intensityPercent?.let { add("intensiteetti $it %") }
    }

    if (compact) {
        Text(
            text = "Kello: ${(effort + body.take(1)).joinToString(" · ")}",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Kello: ${effort.joinToString(" · ")}",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Each line is drawn only when it has something in it — a treadmill run with no strap has
        // no body line at all, and an empty label would be worse than its absence.
        listOf(durations, body, cost).forEach { line ->
            if (line.isNotEmpty()) {
                Text(
                    text = line.joinToString(" · "),
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun WorkoutCardToday(
    workout: Workout,
    onStatusChange: (SessionStatus) -> Unit,
    onMoveToTomorrow: () -> Unit,
    onGuidedProgressChange: (GuidedProgress) -> Unit = {},
    onOpenActiveWorkout: () -> Unit = {},
    onExerciseClick: ((Exercise) -> Unit)? = null,
    completed: CompletedSessionMetrics? = null,
    run: CompletedRunMetrics? = null,
    analysis: AiAnalysisState? = null,
    planProposal: AiPlanProposalState? = null,
    analysisConfigured: Boolean = false,
    onRequestAnalysis: () -> Unit = {},
    onDismissAnalysis: () -> Unit = {},
    onRequestPlanProposal: (String?) -> Unit = {},
    onApplyPlanProposal: () -> Unit = {},
    onDismissPlanProposal: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = workout.type.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WorkoutStatusBadge(workout.status)
                    // In the header rather than beside the primary button below: a completed or
                    // skipped session renders no button row at all, but can still offer
                    // "AI-analyysi: miten meni?" — the menu must not depend on that row existing.
                    SecondaryActionsMenu(
                        kind = AiAnalysisAvailability.kindFor(workout.status, workout.dayOffset),
                        analysisConfigured = analysisConfigured,
                        analysis = analysis,
                        planProposal = planProposal,
                        onRequestAnalysis = onRequestAnalysis,
                        onRequestPlanProposal = onRequestPlanProposal,
                        status = workout.status,
                        appliedLighterVariant = workout.appliedLighterVariant,
                        onStatusChange = onStatusChange,
                        onMoveToTomorrow = onMoveToTomorrow,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // How many movements the session actually has, read the same way the card below reads
            // them: the plan's own list when it has one, the parsed description when it does not.
            //
            // Gated on STRENGTH, and that gate is not decoration. `parseStrengthDescription`
            // decides what a movement is by counting commas, so it reads "Rauhallinen
            // peruskestävyyslenkki, syke alle 145." as two movements and put "Liikkeet 2" on a
            // running card. A session type that has no movements must not be asked how many it has.
            val movementCount =
                if (workout.type != WorkoutType.STRENGTH) 0
                else if (workout.exercises.isNotEmpty()) workout.exercises.size
                else parseStrengthDescription(workout.description).exercises.size

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Suunniteltu klo ${workout.time}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                // The duration moves into the stat row when there is one, so it is not stated twice.
                if (movementCount == 0) {
                    Text(
                        text = "${workout.durationMin} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (movementCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                WorkoutStatColumns(
                    durationMin = workout.durationMin,
                    movements = movementCount,
                    rounds = workout.rounds,
                )
            }

            // Under the plan's own line, so the two read as "asked for" above and "actually done"
            // below rather than competing for the same meaning.
            completed?.let {
                Spacer(modifier = Modifier.height(4.dp))
                CompletedMetricsRow(it)
            }

            // Its own line under Oura's rather than merged into it. The ring and the watch record
            // the same run separately, and averaging or picking between two measurements of the
            // same thing would hide which device said what.
            run?.let {
                Spacer(modifier = Modifier.height(4.dp))
                RunMetricsRow(it)
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            val isInteractive = workout.type == WorkoutType.STRENGTH && workout.status == SessionStatus.STARTED
            val parsedWorkout = remember(workout.description) { parseStrengthDescription(workout.description) }
            // The plan's own movements when it has them, exactly as the read-only list uses them.
            // Reading the description instead was what made a started workout lose the guide links
            // and hand a per-side hold a single clock.
            val fromPlan = workout.exercises.isNotEmpty()
            // Two places to tick the same movements off is one too many. Active Workout Mode owns
            // the progress of a session it can run, so the card falls back to the read-only list
            // and lets the button be the way in. The checklist stays for sessions the full-screen
            // mode cannot take — those whose movements were parsed out of the description rather
            // than authored — because for them it is still the only way to keep count.
            val guided = isInteractive && !fromPlan && parsedWorkout.exercises.isNotEmpty()

            if (guided) {
                if (parsedWorkout.intro.isNotBlank()) {
                    Text(
                        text = parsedWorkout.intro,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                val rounds = if (fromPlan) workout.rounds else parsedWorkout.rounds
                val perRound =
                    if (fromPlan) workout.exercises.size else parsedWorkout.exercises.size
                val total = rounds * perRound

                // How far down the list the session has got, as one number.
                //
                // A workout is a sequence, not a set of independent boxes: the third round of an
                // exercise cannot be done before the second, and a movement ticked off by mistake
                // is undone by walking back, not by reaching into the middle. One counter says
                // all of that — a row is done below it, current at it, and not yet reachable
                // above it — and there is no way to represent an order that never happened.
                //
                // **Keyed by the length of the sequence as well as the session**, because
                // "Kevyempi versio" swaps the movement list under a session that has already been
                // walked part-way through. Carrying the old count across that swap and clamping it
                // reads as further along than it is — three of five movements became "2 / 2 done"
                // on the shorter list, which is a finished workout. A different sequence starts at
                // the beginning.
                var completed by rememberSaveable(workout.id, total) { mutableIntStateOf(0) }
                // Still read through a clamp: the key covers a list that changed length, and this
                // covers everything else.
                val done = completed.coerceIn(0, total)

                // Mirrored upwards on every change, and once on entry so that finishing a workout
                // without ticking anything is still recorded as that rather than as silence.
                //
                // The counter stays here rather than moving to the ViewModel: `rememberSaveable`
                // is what carries it through the process being killed mid-set, and a ViewModel
                // field would not. What is sent up is the clamped value together with the shape it
                // was counted against, so a reader can never pair a count with the wrong list.
                LaunchedEffect(workout.id, done, rounds, perRound) {
                    onGuidedProgressChange(
                        GuidedProgress(done = done, rounds = rounds, perRound = perRound)
                    )
                }

                for (round in 1..rounds) {
                    if (rounds > 1) {
                        Text(
                            text = "Kierros $round",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    for (position in 0 until perRound) {
                        val index = (round - 1) * perRound + position
                        // Keyed by its place in the sequence so each round gets its own row and
                        // its own clock, rather than inheriting the previous round's finished one.
                        key(round, position) {
                            GuidedExerciseRow(
                                exercise =
                                    if (fromPlan) workout.exercises[position]
                                    else parsedWorkout.exercises[position].asExercise(),
                                checked = index < done,
                                // Only the next movement can be ticked, and only the last ticked
                                // one can be unticked.
                                enabled = index == done || index == done - 1,
                                isCurrent = index == done,
                                onCheckedChange = { completed = if (it) index + 1 else index },
                                onExerciseClick = onExerciseClick.takeIf { fromPlan },
                            )
                        }
                    }
                }

                if (parsedWorkout.outro.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = parsedWorkout.outro,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                // Same read-only rendering the expanded Week card uses.
                WorkoutDetails(workout, onExerciseClick = onExerciseClick)
            }

            if (guided && workout.appliedLighterVariant) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Kevennetty versio käytössä.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Above the action buttons rather than below them: on a completed session it is the
            // only thing left to do with the card, and on an upcoming one it is what the button
            // underneath is the answer to. showTriggers = false: the "AI-analyysi" / "Pyydä
            // AI:lta" starting buttons now live in the header's "..." menu (SecondaryActionsMenu)
            // — this still renders the Loading/Loaded/Failed result inline exactly as before, since
            // that is state a person is reading, not an action waiting to be offered.
            AiAnalysisSection(
                kind = AiAnalysisAvailability.kindFor(workout.status, workout.dayOffset),
                state = analysis,
                configured = analysisConfigured,
                onRequest = onRequestAnalysis,
                onDismiss = onDismissAnalysis,
                proposalState = planProposal,
                onRequestProposal = onRequestPlanProposal,
                onApplyProposal = onApplyPlanProposal,
                onDismissProposal = onDismissPlanProposal,
                showTriggers = false,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // One action button. Only offered while the session is still open — a completed,
            // skipped or cancelled session has no legal transition left. Everything that used to
            // stack up beside it (Kevyempi versio, Ohita, Siirrä huomiselle) moved into the
            // header's "..." menu, so this card now has exactly one loud action.
            if (workout.status.isOpen) {
                if (workout.type == WorkoutType.STRENGTH && workout.status != SessionStatus.STARTED) {
                    Button(
                        onClick = onOpenActiveWorkout,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Aloita ohjattu treeni")
                    }
                } else if (workout.type == WorkoutType.STRENGTH && workout.status == SessionStatus.STARTED) {
                    Button(
                        onClick = onOpenActiveWorkout,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Jatka ohjattua treeniä")
                    }
                } else {
                    Button(
                        onClick = { onStatusChange(SessionStatus.COMPLETED) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Merkitse tehdyksi")
                    }
                }
            }
        }
    }
}

/**
 * The "..." menu that replaced the stacked secondary buttons in the header, next to
 * [WorkoutStatusBadge] — not beside the primary button below, because that button does not always
 * exist (a completed session renders none) while "AI-analyysi: miten meni?" should still be
 * reachable there.
 *
 * Every row is conditional on its action actually applying right now — a completed session offers
 * no "Ohita", a session already lightened offers no "Kevyempi versio" again, and once an AI
 * request is in flight or answered its trigger row is gone (the answer renders on the card itself,
 * via [AiAnalysisSection] with `showTriggers = false`). Renders nothing at all, not even the icon,
 * when every row would be conditional-false — the same "an opt-in feature that has not been opted
 * into should be invisible" rule [AiAnalysisSection] already follows for the AI items on their own.
 *
 * "Ohita", "Kevyempi versio" and "Siirrä huomiselle" are further restricted to a session that has
 * not been started: none of the three is in [SessionStatus.STARTED]'s `allowedTransitions`, so
 * offering them there was a dead tap the repository silently rejected. A started session gets
 * "Keskeytä treeni" instead, the only row it can actually act on.
 */
@Composable
private fun SecondaryActionsMenu(
    kind: AiAnalysisKind?,
    analysisConfigured: Boolean,
    analysis: AiAnalysisState?,
    planProposal: AiPlanProposalState?,
    onRequestAnalysis: () -> Unit,
    onRequestPlanProposal: (String?) -> Unit,
    status: SessionStatus,
    appliedLighterVariant: Boolean,
    onStatusChange: (SessionStatus) -> Unit,
    onMoveToTomorrow: () -> Unit,
) {
    val notYetStarted = status.isOpen && status != SessionStatus.STARTED
    val showAnalysisTrigger = kind != null && analysisConfigured && analysis == null
    val showProposalTrigger =
        kind == AiAnalysisKind.UPCOMING && analysisConfigured && planProposal == null
    val showLighten = notYetStarted && !appliedLighterVariant
    val showSkip = notYetStarted
    val showInterrupt = status == SessionStatus.STARTED
    val showPostpone = notYetStarted
    if (!showAnalysisTrigger && !showProposalTrigger && !showLighten && !showSkip &&
        !showInterrupt && !showPostpone
    ) {
        return
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Lisää toimintoja")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showAnalysisTrigger) {
                DropdownMenuItem(
                    text = {
                        Text(
                            when (kind) {
                                AiAnalysisKind.COMPLETED -> "AI-analyysi: miten meni?"
                                AiAnalysisKind.UPCOMING -> "AI-analyysi: miten tämä kannattaa tehdä?"
                                null -> ""
                            }
                        )
                    },
                    onClick = {
                        expanded = false
                        onRequestAnalysis()
                    },
                )
            }
            if (showProposalTrigger) {
                DropdownMenuItem(
                    text = { Text("Pyydä AI:lta muutosehdotus") },
                    onClick = {
                        expanded = false
                        onRequestPlanProposal(null)
                    },
                )
            }
            if (showLighten) {
                DropdownMenuItem(
                    text = { Text("Kevyempi versio") },
                    onClick = {
                        expanded = false
                        onStatusChange(SessionStatus.REPLACED_WITH_LIGHTER_VERSION)
                    },
                )
            }
            if (showSkip) {
                DropdownMenuItem(
                    text = { Text("Ohita") },
                    onClick = {
                        expanded = false
                        onStatusChange(SessionStatus.SKIPPED)
                    },
                )
            }
            if (showInterrupt) {
                DropdownMenuItem(
                    text = { Text("Keskeytä treeni") },
                    onClick = {
                        expanded = false
                        onStatusChange(SessionStatus.INTERRUPTED)
                    },
                )
            }
            if (showPostpone) {
                DropdownMenuItem(
                    text = { Text("Siirrä huomiselle") },
                    onClick = {
                        expanded = false
                        onMoveToTomorrow()
                    },
                )
            }
        }
    }
}

/**
 * One movement of a started workout: tick it off, see what it asks for, open its guide, and run
 * its clock as many times as the movement actually needs.
 *
 * The clock only appears on the movement you are actually on. That is what makes the sequence
 * real rather than advisory — and it is also why there is no "Valmis / Alusta" left to read: the
 * last round ticks the row, the row stops being current, and the clock goes with it. Untick the
 * row and it comes back at the first side, because the clock's state lives only as long as it is
 * on screen.
 */
@Composable
private fun GuidedExerciseRow(
    exercise: Exercise,
    checked: Boolean,
    enabled: Boolean,
    isCurrent: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onExerciseClick: ((Exercise) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            ExerciseNameRow(
                text = exercise.name,
                onClick = onExerciseClick?.let { open -> { open(exercise) } },
            )
            val prescription = exercise.prescription()
            if (prescription.isNotEmpty()) {
                Text(
                    text = prescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCurrent && exercise.durationSec != null) {
                Spacer(modifier = Modifier.height(4.dp))
                // The last round ticks the movement off, so finishing the clock and marking it
                // done are the same act rather than two things to remember.
                ExerciseTimer(
                    exercise = exercise,
                    onAllRoundsCompleted = { onCheckedChange(true) },
                )
            }
        }
    }
}

/**
 * A movement read out of the description, dressed as one the plan wrote.
 *
 * Only the duration survives the guess — `parseStrengthDescription` decides a movement is timed by
 * finding "lankku" in its name — but it is enough to hand the same clock to plans written before
 * the `exercises` array existed, instead of keeping a second timer alive for them.
 */
private fun ParsedExercise.asExercise(): Exercise =
    Exercise(name = name, durationSec = plankDurationSeconds?.takeIf { isPlank })

@Composable
fun WorkoutStatusBadge(status: SessionStatus) {
    val (color, textColor) = when (status) {
        SessionStatus.PLANNED -> ColorGray.copy(alpha = 0.2f) to ColorGray
        SessionStatus.NOTIFIED -> ColorBlue.copy(alpha = 0.2f) to ColorBlue
        SessionStatus.STARTED -> ColorBlue.copy(alpha = 0.2f) to ColorBlue
        SessionStatus.COMPLETED -> ColorGreen.copy(alpha = 0.2f) to ColorGreen
        SessionStatus.SKIPPED -> ColorRed.copy(alpha = 0.2f) to ColorRed
        SessionStatus.INTERRUPTED -> ColorRed.copy(alpha = 0.2f) to ColorRed
        SessionStatus.RESCHEDULED -> ColorGray.copy(alpha = 0.2f) to ColorGray
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION ->
            ColorYellow.copy(alpha = 0.2f) to Color(0xFFF57F17)
        SessionStatus.PAUSED_DUE_TO_ILLNESS -> ColorYellow.copy(alpha = 0.2f) to Color(0xFFF57F17)
        SessionStatus.CANCELLED -> ColorGray.copy(alpha = 0.2f) to ColorGray
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.title,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold
        )
    }
}

data class ParsedWorkout(
    val intro: String,
    val exercises: List<ParsedExercise>,
    val outro: String,
    val rounds: Int = 1
)

data class ParsedExercise(
    val name: String,
    val isPlank: Boolean,
    val plankDurationSeconds: Int?
)

fun extractDuration(text: String): Int? {
    val match = Regex("""(\d+)\s*s""").find(text.lowercase())
    return match?.groupValues?.get(1)?.toIntOrNull()
}

fun extractRounds(text: String): Int {
    val match = Regex("""(\d+)\s*kierros""").find(text.lowercase())
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
}

fun parseStrengthDescription(desc: String): ParsedWorkout {
    val rounds = extractRounds(desc)
    val cleanDesc = desc.replace("\\,", ",")
    val parts = cleanDesc.split(Regex("""(?<=\.)\s+|\n+""")).filter { it.isNotBlank() }
    
    val exerciseSentence = parts.maxByOrNull { it.count { c -> c == ',' } }
    
    if (exerciseSentence == null || exerciseSentence.count { it == ',' } == 0) {
        val exercises = parts.map { 
            ParsedExercise(it.trim(), it.lowercase().contains("lankku"), extractDuration(it)) 
        }
        return ParsedWorkout("", exercises, "", rounds)
    }
    
    val intro = parts.takeWhile { it != exerciseSentence }.joinToString(" ")
    val outro = parts.takeLastWhile { it != exerciseSentence }.joinToString(" ")
    
    val exerciseStrings = exerciseSentence.removeSuffix(".").split(",")
    val exercises = exerciseStrings.map { ex -> 
        val name = ex.trim()
        val lower = name.lowercase()
        ParsedExercise(name, lower.contains("lankku"), extractDuration(name))
    }
    
    return ParsedWorkout(intro, exercises, outro, rounds)
}
