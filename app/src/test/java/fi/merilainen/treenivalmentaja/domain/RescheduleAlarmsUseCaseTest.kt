package fi.merilainen.treenivalmentaja.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.data.alarm.ReminderScheduler
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class FakeReminderScheduler(context: Context) : ReminderScheduler(context) {
    val scheduled = mutableListOf<Triple<String, Long, Int>>()

    override fun schedule(sessionId: String, remindAtUtc: Long, requestCode: Int) {
        scheduled.add(Triple(sessionId, remindAtUtc, requestCode))
    }

    override fun cancelAll(requestCodes: List<Int>) {}
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RescheduleAlarmsUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var settingsStore: NotificationSettingsStore
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var useCase: RescheduleAlarmsUseCase
    private lateinit var resolveReminderUseCase: ResolveReminderUseCase

    private var testNow: Long = 0

    @Before
    fun setup() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsStore = NotificationSettingsStore(context)
        scheduler = FakeReminderScheduler(context)
        resolveReminderUseCase = ResolveReminderUseCase()
        useCase = RescheduleAlarmsUseCase(
            db,
            db.trainingPlanDao(),
            db.workoutSessionDao(),
            settingsStore,
            resolveReminderUseCase,
            scheduler
        )

        testNow = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        
        val sPast = fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity(
            id = "s-past", planId = "p1", originalSessionId = null, scheduledDate = "2026-01-01", scheduledTime = null,
            durationMin = 1, type = fi.merilainen.treenivalmentaja.domain.WorkoutType.RUNNING, timeIsFixed = false,
            intensity = null, weekNumber = 1, distanceKm = null, description = null, reminderOverride = null, status = fi.merilainen.treenivalmentaja.domain.SessionStatus.PLANNED,
            remindAtUtc = testNow - day, updatedAt = testNow
        )
        val s3Days = sPast.copy(id = "s-3", remindAtUtc = testNow + 3 * day, scheduledDate = java.time.LocalDate.now().plusDays(3).toString())
        val s5Days = sPast.copy(id = "s-5", remindAtUtc = testNow + 5 * day, scheduledDate = java.time.LocalDate.now().plusDays(5).toString())
        val s10Days = sPast.copy(id = "s-10", remindAtUtc = testNow + 10 * day, scheduledDate = java.time.LocalDate.now().plusDays(10).toString())
        val sNotPlanned = sPast.copy(id = "s-not-planned", remindAtUtc = testNow + 4 * day, scheduledDate = java.time.LocalDate.now().plusDays(4).toString(), status = fi.merilainen.treenivalmentaja.domain.SessionStatus.COMPLETED)
        
        val p1 = fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity(id = "p1", name = "Plan", description = null, timeZone = "Europe/Helsinki", isActive = true, schemaVersion = 1, startDate = "2026-01-01", contentHash = "hash", createdAt = testNow)
        db.trainingPlanDao().insert(p1)
        db.workoutSessionDao().insert(sPast)
        db.workoutSessionDao().insert(s3Days)
        db.workoutSessionDao().insert(s5Days)
        db.workoutSessionDao().insert(s10Days)
        db.workoutSessionDao().insert(sNotPlanned)
    }

    @After
    fun teardown() {
        db.close()
    }

    /**
     * Importing a plan deactivates the previous one but leaves its rows in the database, so a
     * replaced programme used to keep its alarms: one morning arrived with a reminder for week
     * 4/8 of the current plan and another for week 1/8 of the plan it had replaced.
     */
    @Test
    fun ignoresSessionsBelongingToAReplacedPlan() = runTest {
        val day = 24L * 60 * 60 * 1000
        val oldPlan = fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity(
            id = "p0", name = "Vanha ohjelma", description = null, timeZone = "Europe/Helsinki",
            isActive = false, schemaVersion = 1, startDate = "2026-01-01", contentHash = "old",
            createdAt = testNow
        )
        db.trainingPlanDao().insert(oldPlan)
        db.workoutSessionDao().insert(
            fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity(
                id = "s-ghost", planId = "p0", originalSessionId = null,
                scheduledDate = java.time.LocalDate.now().plusDays(2).toString(),
                scheduledTime = null, durationMin = 1,
                type = fi.merilainen.treenivalmentaja.domain.WorkoutType.STRENGTH,
                timeIsFixed = false, intensity = null, weekNumber = 1, distanceKm = null,
                description = null, reminderOverride = null,
                status = fi.merilainen.treenivalmentaja.domain.SessionStatus.PLANNED,
                remindAtUtc = testNow + 2 * day, updatedAt = testNow
            )
        )

        useCase.execute()

        val scheduledIds = scheduler.scheduled.map { it.first }
        assertTrue("the replaced plan must not be scheduled", !scheduledIds.contains("s-ghost"))
        assertTrue("the active plan must still be scheduled", scheduledIds.contains("s-3"))
    }

    @Test
    fun schedulesSessionWithin7Days() = runTest {
        useCase.execute()
        val scheduledIds = scheduler.scheduled.map { it.first }
        assertTrue(scheduledIds.contains("s-3"))
        assertTrue(scheduledIds.contains("s-5"))
    }

    @Test
    fun skipsSessionOutside7Days() = runTest {
        useCase.execute()
        val scheduledIds = scheduler.scheduled.map { it.first }
        assertTrue(!scheduledIds.contains("s-10"))
    }

    @Test
    fun skipsPastSession() = runTest {
        useCase.execute()
        val scheduledIds = scheduler.scheduled.map { it.first }
        assertTrue(!scheduledIds.contains("s-past"))
    }

    @Test
    fun skipsNotPlannedSession() = runTest {
        useCase.execute()
        val scheduledIds = scheduler.scheduled.map { it.first }
        assertTrue(!scheduledIds.contains("s-not-planned"))
    }

    @Test
    fun assignsDifferentRequestCodesToDifferentSessions() = runTest {
        useCase.execute()
        val s3Code = scheduler.scheduled.first { it.first == "s-3" }.third
        val s5Code = scheduler.scheduled.first { it.first == "s-5" }.third
        assertTrue(s3Code != s5Code)
    }

    @Test
    fun schedulesRearmWithHighestRequestCode() = runTest {
        useCase.execute()
        val rearm = scheduler.scheduled.first { it.first == "REARM" }
        assertEquals(scheduler.scheduled.maxOf { it.third }, rearm.third)
    }
}
