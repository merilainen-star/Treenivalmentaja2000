package fi.merilainen.treenivalmentaja.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import fi.merilainen.treenivalmentaja.OuraCard
import fi.merilainen.treenivalmentaja.SettingsScreenContent
import fi.merilainen.treenivalmentaja.TodayScreenContent
import fi.merilainen.treenivalmentaja.WeekScreenContent
import fi.merilainen.treenivalmentaja.Workout
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings
import fi.merilainen.treenivalmentaja.domain.Exercise
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
 * The three screens, whole.
 *
 * Until the screens were split into a stateless `…Content` and a thin ViewModel wrapper, none of
 * this could be captured: every screen took a `WorkoutViewModel` directly, so a test would have
 * had to stand up a repository, a database, an alarm scheduler and two use cases to render a list
 * of cards. Screenshot cover therefore stopped at the individual cards, and how they sit together
 * — spacing, order, what an empty day looks like, whether a dialog covers the thing it is asking
 * about — was verified by looking at a phone and remembering.
 *
 * These captures are deliberately of states that are awkward to reach by hand: a rest day, a
 * missing notification permission, a plan waiting to be confirmed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ScreenScreenshotTest {

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.005f)
    )

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = options) {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    content()
                }
            }
        }
    }

    private fun strength(dayOffset: Int, id: String) = Workout(
        id = id,
        dayOffset = dayOffset,
        type = WorkoutType.STRENGTH,
        time = "09:00",
        durationMin = 20,
        description = "Palauttava core ja liikkuvuus. Viikko 4/8. Lämmittely 2 min.",
        exercises = listOf(
            Exercise(name = "Kissanlehmä", reps = 10, guide = GuideRef("wger", "1938")),
            Exercise(
                name = "Sivulankku",
                durationSec = 20,
                perSide = true,
                guide = GuideRef("wger", "580"),
            ),
        ),
    )

    private fun run(dayOffset: Int, id: String, status: SessionStatus = SessionStatus.PLANNED) =
        Workout(
            id = id,
            dayOffset = dayOffset,
            type = WorkoutType.RUNNING,
            time = "12:00",
            durationMin = 36,
            description = "Reippaampi juoksu – 6 km. Viikko 4/8.",
            status = status,
        )

    // ------------------------------------------------------------------ Today

    @Test
    fun today() = capture("screen_today") {
        TodayScreenContent(
            workouts = listOf(
                strength(0, "s-24"),
                run(0, "s-66", status = SessionStatus.COMPLETED),
            )
        )
    }

    /** A rest day. Easy to write, awkward to reach on a phone without editing the plan. */
    @Test
    fun today_restDay() = capture("screen_today_rest_day") {
        TodayScreenContent(workouts = listOf(strength(1, "s-25")))
    }

    // ------------------------------------------------------------------ Week

    @Test
    fun week() = capture("screen_week") {
        WeekScreenContent(
            workouts = listOf(
                strength(0, "s-24"),
                run(0, "s-66"),
                strength(1, "s-25"),
                strength(3, "s-27"),
                run(3, "s-67"),
            )
        )
    }

    // ------------------------------------------------------------------ Settings

    @Test
    fun settings() = capture("screen_settings") {
        SettingsScreenContent(
            settings = NotificationSettings(),
            updateStatus = UpdateStatus.UpToDate("1.0-6b24a69"),
        )
    }

    /**
     * Without the notification permission the reminders do not fire at all, so the screen says so
     * in red above everything else. Granting it is a system dialog, which makes this state hard to
     * get back to once it has been answered.
     */
    @Test
    fun settings_withoutNotificationPermission() = capture("screen_settings_no_permission") {
        SettingsScreenContent(
            settings = NotificationSettings(),
            updateStatus = UpdateStatus.LocalBuild,
            hasNotificationPermission = false,
        )
    }

    /**
     * Settings with Oura connected — the state a build with an `.env` and a finished login reaches,
     * and one that cannot be produced on this machine at all: it needs credentials only the owner's
     * Oura account can issue. Captured from the state rather than from a phone, which is the whole
     * reason the screens were made functions of their state.
     */
    @Test
    fun settings_ouraConnected() = capture("screen_settings_oura_connected") {
        SettingsScreenContent(
            settings = NotificationSettings(),
            updateStatus = UpdateStatus.UpToDate("1.0-6b24a69"),
            ouraState = OuraConnectionState.Connected,
        )
    }

    // ------------------------------------------------------------------ the Oura card

    /** The ordinary starting point once a build has credentials. */
    @Test
    fun oura_disconnected() = capture("card_oura_disconnected") {
        OuraCard(state = OuraConnectionState.Disconnected)
    }

    /**
     * The one-off setup, and the state a fresh install opens on. Everything here is done on the
     * phone: Oura withdrew personal access tokens, so an application registered in their developer
     * portal is the only way in, and its client id and secret are typed rather than compiled in.
     */
    @Test
    fun oura_credentialsNeeded() = capture("card_oura_not_configured") {
        OuraCard(state = OuraConnectionState.NotConfigured)
    }

    /** Waiting on a browser that is covering this screen. */
    @Test
    fun oura_connecting() = capture("card_oura_connecting") {
        OuraCard(state = OuraConnectionState.Connecting)
    }

    @Test
    fun oura_connected() = capture("card_oura_connected") {
        OuraCard(state = OuraConnectionState.Connected)
    }

    /**
     * A redirect whose `state` did not match — the one failure that is a refusal rather than a
     * fault, and the hardest of all these to reach by hand.
     */
    @Test
    fun oura_failed() = capture("card_oura_failed") {
        OuraCard(
            state =
                OuraConnectionState.Failed(
                    "Vastaus ei vastannut lähetettyä pyyntöä, joten sitä ei käytetty."
                )
        )
    }
}
