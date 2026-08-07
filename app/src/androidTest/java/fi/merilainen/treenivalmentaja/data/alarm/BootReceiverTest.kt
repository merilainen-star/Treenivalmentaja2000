package fi.merilainen.treenivalmentaja.data.alarm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The receiver re-arms alarms, which is observable as a rewritten `remindAtUtc` on a PLANNED
 * session: [fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase] resolves the reminder
 * from the notification settings and persists it when it differs from the stored value.
 */
@RunWith(AndroidJUnit4::class)
class BootReceiverTest {

    private lateinit var app: TreenivalmentajaApplication
    private lateinit var receiver: BootReceiver

    /** Deliberately wrong, so any re-arm overwrites it. */
    private val staleRemindAt = 1L

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        receiver = BootReceiver()

        app.db.clearAllTables()
        app.db.trainingPlanDao().insert(
            TrainingPlanEntity(
                id = "plan1", name = "Test Plan", schemaVersion = 1,
                timeZone = "Europe/Helsinki", startDate = LocalDate.now().toString(),
                description = null, createdAt = 1000, contentHash = "hash", isActive = true
            )
        )
        app.db.workoutSessionDao().insert(
            WorkoutSessionEntity(
                id = "s-1", planId = "plan1", type = WorkoutType.RUNNING, weekNumber = 1,
                scheduledDate = LocalDate.now().plusDays(2).toString(), scheduledTime = "18:00",
                remindAtUtc = staleRemindAt, timeIsFixed = false, durationMin = 60,
                description = "Run", status = SessionStatus.PLANNED, updatedAt = 1000
            )
        )
    }

    @Test
    fun bootCompletedRearmsAlarms() = runBlocking {
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(1500)

        val session = app.db.workoutSessionDao().getById("s-1")
        assertNotEquals(staleRemindAt, session?.remindAtUtc)
    }

    @Test
    fun unknownActionIsIgnored() = runBlocking {
        receiver.onReceive(app, Intent("fi.merilainen.treenivalmentaja.SPOOFED"))
        Thread.sleep(1500)

        val session = app.db.workoutSessionDao().getById("s-1")
        assertEquals(staleRemindAt, session?.remindAtUtc)
    }

    @Test
    fun intentWithNoActionIsIgnored() = runBlocking {
        receiver.onReceive(app, Intent())
        Thread.sleep(1500)

        val session = app.db.workoutSessionDao().getById("s-1")
        assertEquals(staleRemindAt, session?.remindAtUtc)
    }
}
