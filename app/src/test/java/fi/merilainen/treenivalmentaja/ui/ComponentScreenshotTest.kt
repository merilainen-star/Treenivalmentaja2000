package fi.merilainen.treenivalmentaja.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import fi.merilainen.treenivalmentaja.RecoveryCard
import fi.merilainen.treenivalmentaja.RecoveryState
import fi.merilainen.treenivalmentaja.Workout
import fi.merilainen.treenivalmentaja.WorkoutCardToday
import fi.merilainen.treenivalmentaja.WorkoutCardWeek
import fi.merilainen.treenivalmentaja.WorkoutStatusBadge
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import fi.merilainen.treenivalmentaja.ui.theme.MyApplicationTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual regression cover for the stateless building blocks of the Today and Week screens.
 *
 * The screens themselves take a `WorkoutViewModel`, so they cannot be rendered here without
 * standing up a repository and a database; the cards below are where the layout complexity
 * actually lives. See `docs/TESTING.md`.
 *
 * Roborazzi's own composable entry point is used rather than `createComposeRule()`: the default
 * host activity resolves the launcher icon, and Robolectric cannot load an `adaptive-icon` XML
 * from `mipmap-anydpi-v26`, so every capture failed before reaching the composable.
 *
 * Record baselines with `./gradlew :app:recordRoborazziDebug`, check against them with
 * `./gradlew :app:verifyRoborazziDebug`. Never re-record without looking at the diff — a
 * baseline refreshed on reflex protects nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ComponentScreenshotTest {

    private val runningWorkout = Workout(
        id = "w-run",
        dayOffset = 0,
        type = WorkoutType.RUNNING,
        time = "16:00",
        durationMin = 45,
        description = "Rauhallinen peruskestävyyslenkki, syke alle 145.",
        status = SessionStatus.PLANNED,
    )

    private val strengthWorkout = Workout(
        id = "w-gym",
        dayOffset = 1,
        type = WorkoutType.STRENGTH,
        time = "07:00",
        durationMin = 40,
        description = "3 kierrosta. Kyykky, penkkipunnerrus, leuanveto, lankku 60 s. Venyttely lopuksi.",
        status = SessionStatus.PLANNED,
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png") {
            MyApplicationTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Today card

    @Test
    fun todayCard_running_planned() = capture("today_card_running_planned") {
        WorkoutCardToday(runningWorkout, onStatusChange = {}, onMoveToTomorrow = {})
    }

    /**
     * `parseStrengthDescription` splits the free-text description into rounds and exercises, so
     * this baseline pins the parser's output as it is actually laid out. The plank timer is not
     * part of it — that row only appears once the guided workout is started.
     */
    @Test
    fun todayCard_strength_parsedRounds() = capture("today_card_strength_parsed") {
        WorkoutCardToday(strengthWorkout, onStatusChange = {}, onMoveToTomorrow = {})
    }

    @Test
    fun todayCard_completed() = capture("today_card_completed") {
        WorkoutCardToday(
            runningWorkout.copy(status = SessionStatus.COMPLETED),
            onStatusChange = {},
            onMoveToTomorrow = {},
        )
    }

    @Test
    fun todayCard_pausedDueToIllness() = capture("today_card_paused_illness") {
        WorkoutCardToday(
            runningWorkout.copy(status = SessionStatus.PAUSED_DUE_TO_ILLNESS),
            onStatusChange = {},
            onMoveToTomorrow = {},
        )
    }

    /** The two markers the engine sets on a recovery session: lighter variant and moved-here. */
    @Test
    fun todayCard_lighterVariantMovedHere() = capture("today_card_lighter_moved") {
        WorkoutCardToday(
            runningWorkout.copy(
                status = SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
                appliedLighterVariant = true,
                movedHere = true,
            ),
            onStatusChange = {},
            onMoveToTomorrow = {},
        )
    }

    // ------------------------------------------------------------------ Week card

    @Test
    fun weekCards_allTypes() = capture("week_cards_all_types") {
        WorkoutCardWeek(runningWorkout)
        WorkoutCardWeek(strengthWorkout)
        WorkoutCardWeek(
            runningWorkout.copy(
                id = "w-ski",
                dayOffset = 3,
                type = WorkoutType.SKIING,
                description = "Hiihtolenkki, tasainen maasto.",
            )
        )
    }

    /** Tapping a week row unrolls the session's content beneath it; the chevron flips. */
    @Test
    fun weekCard_expanded_strength() = capture("week_card_expanded_strength") {
        WorkoutCardWeek(strengthWorkout, expanded = true, onToggle = {})
    }

    /** A run has no parsed movements, so the description is shown as written. */
    @Test
    fun weekCard_expanded_running() = capture("week_card_expanded_running") {
        WorkoutCardWeek(runningWorkout, expanded = true, onToggle = {})
    }

    // ------------------------------------------------------------------ Recovery card

    @Test
    fun recoveryCard_allStates() = capture("recovery_card_all_states") {
        RecoveryState.entries.forEach { state ->
            RecoveryCard(state, onSickClicked = {}, onRecoveredClicked = {})
        }
    }

    // ------------------------------------------------------------------ Status badges

    /** Every status the state machine can put on a session, so no colour pair drifts unseen. */
    @Test
    fun statusBadges_allStatuses() = capture("status_badges_all") {
        SessionStatus.entries.forEach { WorkoutStatusBadge(it) }
    }
}
