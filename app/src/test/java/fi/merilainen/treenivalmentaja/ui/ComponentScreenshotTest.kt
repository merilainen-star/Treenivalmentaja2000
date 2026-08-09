package fi.merilainen.treenivalmentaja.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import fi.merilainen.treenivalmentaja.ExerciseGuideSheetContent
import fi.merilainen.treenivalmentaja.ImportStartDialog
import fi.merilainen.treenivalmentaja.RecoveryCard
import fi.merilainen.treenivalmentaja.Workout
import fi.merilainen.treenivalmentaja.WorkoutCardToday
import fi.merilainen.treenivalmentaja.WorkoutCardWeek
import fi.merilainen.treenivalmentaja.UpdateCard
import fi.merilainen.treenivalmentaja.WorkoutStatusBadge
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState
import fi.merilainen.treenivalmentaja.domain.ExerciseSet
import fi.merilainen.treenivalmentaja.domain.GuideRef
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.UpdateStatus
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

    /**
     * Tolerate the sub-pixel text antialiasing that differs between the machine baselines are
     * recorded on and the Linux CI runner. Measured against the runner's output: at most 0.046%
     * of pixels differ, and not one of them by more than 32/255 in any channel — the images are
     * indistinguishable by eye. 0.5% leaves an order of magnitude of headroom over that noise
     * while staying far below any change a person would notice; a 4dp-to-16dp shadow, the change
     * used to prove these tests bite, moves vastly more.
     */
    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = options) {
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

    /**
     * Two buttons and nothing else. The coloured indicator above them used to read the same
     * verdict every day from a constant, so it is gone until Oura can produce a real one.
     */
    @Test
    fun recoveryCard() = capture("recovery_card") {
        RecoveryCard(onSickClicked = {}, onRecoveredClicked = {})
    }

    // ------------------------------------------------------------------ Timed exercises

    /**
     * A session whose movements come from the plan's `exercises` array rather than from parsing
     * the description: the holds carry clocks, and the per-side ones say which side is running.
     */
    @Test
    fun todayCard_timedExercisesFromThePlan() = capture("today_card_timed_exercises") {
        WorkoutCardToday(
            strengthWorkout.copy(
                description = "Liikkuvuus ja keskivartalo. Tauko 30 s liikkeiden välissä.",
                rounds = 1,
                exercises = listOf(
                    Exercise(name = "Kissa-lehmä", reps = 10),
                    Exercise(name = "Lonkankoukistajan venytys", durationSec = 30, perSide = true),
                    Exercise(name = "Bird dog", reps = 10, perSide = true),
                    Exercise(name = "Lankku", durationSec = 25),
                    Exercise(name = "Sivulankku", durationSec = 20, perSide = true),
                ),
            ),
            onStatusChange = {},
            onMoveToTomorrow = {},
        )
    }

    /**
     * A gym session in the shape a strength log actually takes: each exercise's sets done
     * consecutively rather than in circuits, and a ramp where both the load and the reps move.
     */
    @Test
    fun todayCard_gymSessionWithLoads() = capture("today_card_gym_loads") {
        WorkoutCardToday(
            strengthWorkout.copy(
                description = "Huoltava koko vartalon harjoitus.",
                rounds = 1,
                exercises = listOf(
                    Exercise(
                        name = "Alasoutu",
                        setPlan = listOf(
                            ExerciseSet(weightKg = 25.0, reps = 10),
                            ExerciseSet(weightKg = 35.0, reps = 10),
                            ExerciseSet(weightKg = 45.0, reps = 10),
                            ExerciseSet(weightKg = 55.0, reps = 10),
                        ),
                    ),
                    Exercise(name = "Face pull", sets = 3, reps = 12, weightKg = 17.5),
                    Exercise(name = "Hauiskääntö", sets = 3, reps = 10, weightKg = 18.0),
                    Exercise(
                        name = "Nautilus yhden jalan jalkaprässi",
                        perSide = true,
                        setPlan = listOf(
                            ExerciseSet(weightKg = 36.0, reps = 15),
                            ExerciseSet(weightKg = 55.0, reps = 12),
                            ExerciseSet(weightKg = 73.0, reps = 10),
                        ),
                    ),
                ),
            ),
            onStatusChange = {},
            onMoveToTomorrow = {},
        )
    }

    // ------------------------------------------------------------------ Update card

    /** The three states the version card can be read in; "checking" is transient. */
    @Test
    fun updateCard_states() = capture("update_card_states") {
        UpdateCard(status = UpdateStatus.UpToDate("1.0-c07cfac"), onCheck = {})
        UpdateCard(
            status = UpdateStatus.Available(
                versionName = "1.0-a1b2c3d",
                apkUrl = "https://example.invalid/app.apk",
                sizeMb = 19,
            ),
            onCheck = {},
        )
        UpdateCard(status = UpdateStatus.Failed("GitHub vastasi HTTP 503"), onCheck = {})
    }

    // ------------------------------------------------------------------ Import dialog

    /** Both readings of a plan file's dates, offered once at the moment of import. */
    @Test
    fun importStartDialog() = capture("import_start_dialog") {
        ImportStartDialog(onDismiss = {}, onConfirm = {})
    }

    // ------------------------------------------------------------------ Exercise guide sheet

    private val benchPress = ExerciseGuide(
        id = "EIeI8Vf",
        name = "barbell bench press",
        imageUrl = "https://static.exercisedb.dev/media/EIeI8Vf.gif",
        instructions = listOf(
            "Lie flat on a bench with your feet flat on the ground.",
            "Grasp the barbell with an overhand grip slightly wider than shoulder-width apart.",
            "Lower the barbell slowly towards your chest, keeping your elbows tucked in.",
        ),
        targetMuscles = listOf("pectorals"),
        equipment = listOf("barbell"),
        attribution = "Liiketiedot: ExerciseDB / AscendAPI",
    )

    /** wger credits each image's author separately, so its line is longer and wraps differently. */
    private val sidePlank = ExerciseGuide(
        id = "580",
        name = "Side Plank",
        imageUrl = "https://wger.de/media/exercise-images/580/side-plank.png",
        instructions = listOf(
            "Lie on your side with your legs straight and your elbow directly below your shoulder.",
            "Raise your hips until your body forms a straight line from head to feet.",
        ),
        targetMuscles = listOf("Obliquus externus abdominis"),
        equipment = listOf("none (bodyweight exercise)"),
        attribution = "Liiketiedot: wger.de (CC-BY-SA 4) · kuva: Settebello",
    )

    /**
     * The animation is a network GIF, so it can never be part of a baseline. A fixed stand-in of
     * the real height keeps the rest of the sheet laid out as it will be on the phone, and keeps
     * the capture from depending on how fast a request failed.
     */
    private val stubAnimation: @Composable (String) -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }

    private fun guideSheet(name: String, state: ExerciseGuideState) = capture(name) {
        ExerciseGuideSheetContent(
            state = state,
            onRetry = {},
            onSelectSuggestion = {},
            animation = stubAnimation,
        )
    }

    @Test
    fun guideSheet_loaded() = guideSheet(
        "guide_sheet_loaded",
        ExerciseGuideState.Loaded("Penkkipunnerrus", benchPress),
    )

    /** A name-search hit has to keep saying it is one; the plan never claimed this movement. */
    @Test
    fun guideSheet_loadedFromASuggestion() = guideSheet(
        "guide_sheet_suggested",
        ExerciseGuideState.Loaded("Penkkipunnerrus", benchPress, suggested = true),
    )

    /** A movement wger has and ExerciseDB does not, credited to the image's own author. */
    @Test
    fun guideSheet_loadedFromWger() = guideSheet(
        "guide_sheet_wger",
        ExerciseGuideState.Loaded("Sivulankku", sidePlank),
    )

    /** Hits pooled from both sources, so both credit lines have to appear. */
    @Test
    fun guideSheet_suggestions() = guideSheet(
        "guide_sheet_suggestions",
        ExerciseGuideState.Suggestions(
            exerciseName = "Lankku",
            matches = listOf(
                sidePlank.copy(id = "458", name = "Plank"),
                benchPress.copy(id = "a", name = "front plank with twist"),
                benchPress.copy(id = "c", name = "kneeling plank tap shoulder (male)"),
            ),
        ),
    )

    /** Offline is a normal state: the session keeps working, and the guide offers a retry. */
    @Test
    fun guideSheet_offline() = guideSheet(
        "guide_sheet_offline",
        ExerciseGuideState.Unavailable(
            exerciseName = "Bird dog",
            message = "Liiketiedot vaativat verkkoyhteyden.",
            canRetry = true,
        ),
    )

    /** Nothing to retry here, so no button is offered. */
    @Test
    fun guideSheet_notFound() = guideSheet(
        "guide_sheet_not_found",
        ExerciseGuideState.Unavailable(
            exerciseName = "Kissa-lehmä",
            message = "Ei osumaa. Lisää liikkeelle guide-viite suunnitelmaan.",
            canRetry = false,
        ),
    )

    /** The rows the sheet opens from: tappable, and marked as such. */
    @Test
    fun todayCard_exercisesAreTappable() = capture("today_card_exercises_tappable") {
        WorkoutCardToday(
            strengthWorkout.copy(
                description = "Liikkuvuus ja keskivartalo.",
                rounds = 1,
                exercises = listOf(
                    Exercise(name = "Kissa-lehmä", reps = 10),
                    Exercise(
                        name = "Penkkipunnerrus",
                        sets = 3,
                        reps = 8,
                        weightKg = 40.0,
                        guide = GuideRef(provider = "exercisedb", id = "EIeI8Vf"),
                    ),
                ),
            ),
            onStatusChange = {},
            onMoveToTomorrow = {},
            onExerciseClick = {},
        )
    }

    /**
     * A started workout, drawn from the plan's own movements rather than from its description.
     *
     * Three things at once, all of which were wrong or missing before: every movement is tappable
     * for its guide, the movement you are on carries a clock that knows a per-side hold runs
     * twice ("Vasen 1/2"), and the ones below it are locked until it is done — a workout is a
     * sequence, and the checkboxes now say so.
     *
     * The timed movement is first on purpose: the clock belongs to the current row alone, so a
     * capture of the opening state only shows one if the first movement has one.
     */
    @Test
    fun todayCard_startedFromThePlan() = capture("today_card_started_from_plan") {
        WorkoutCardToday(
            strengthWorkout.copy(
                status = SessionStatus.STARTED,
                description = "Palauttava core ja liikkuvuus. Tauko 30 s liikkeiden välissä.",
                rounds = 1,
                exercises = listOf(
                    Exercise(
                        name = "Sivulankku",
                        durationSec = 20,
                        perSide = true,
                        guide = GuideRef("wger", "580"),
                    ),
                    Exercise(name = "Kissanlehmä", reps = 10, guide = GuideRef("wger", "1938")),
                    Exercise(
                        name = "Bird dog",
                        reps = 10,
                        perSide = true,
                        guide = GuideRef("wger", "1572"),
                    ),
                ),
            ),
            onStatusChange = {},
            onMoveToTomorrow = {},
            onExerciseClick = {},
        )
    }

    // ------------------------------------------------------------------ Status badges

    /** Every status the state machine can put on a session, so no colour pair drifts unseen. */
    @Test
    fun statusBadges_allStatuses() = capture("status_badges_all") {
        SessionStatus.entries.forEach { WorkoutStatusBadge(it) }
    }
}
