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

    @Before
    fun setup() {
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
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testScheduling() = runTest {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        
        val sPast = fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity(
            id = "s-past", planId = "p1", originalSessionId = null, scheduledDate = "2026-01-01", scheduledTime = null,
            durationMin = 1, type = fi.merilainen.treenivalmentaja.domain.WorkoutType.RUNNING, timeIsFixed = false,
            intensity = null, weekNumber = 1, distanceKm = null, description = null, reminderOverride = null, status = fi.merilainen.treenivalmentaja.domain.SessionStatus.PLANNED,
            remindAtUtc = now - day, updatedAt = now
        )
        val s3Days = sPast.copy(id = "s-3", remindAtUtc = now + 3 * day, scheduledDate = java.time.LocalDate.now().plusDays(3).toString())
        val s5Days = sPast.copy(id = "s-5", remindAtUtc = now + 5 * day, scheduledDate = java.time.LocalDate.now().plusDays(5).toString())
        val s10Days = sPast.copy(id = "s-10", remindAtUtc = now + 10 * day, scheduledDate = java.time.LocalDate.now().plusDays(10).toString())
        val sNotPlanned = sPast.copy(id = "s-not-planned", remindAtUtc = now + 4 * day, scheduledDate = java.time.LocalDate.now().plusDays(4).toString(), status = fi.merilainen.treenivalmentaja.domain.SessionStatus.COMPLETED)
        
        val p1 = fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity(id = "p1", name = "Plan", description = null, timeZone = "Europe/Helsinki", isActive = true, schemaVersion = 1, startDate = "2026-01-01", contentHash = "hash", createdAt = now)
        db.trainingPlanDao().insert(p1)
        db.workoutSessionDao().insert(sPast)
        db.workoutSessionDao().insert(s3Days)
        db.workoutSessionDao().insert(s5Days)
        db.workoutSessionDao().insert(s10Days)
        db.workoutSessionDao().insert(sNotPlanned)

        useCase.execute()

        val scheduledIds = scheduler.scheduled.map { it.first }
        
        // Assert 1: 3 days is scheduled
        assertTrue(scheduledIds.contains("s-3"))
        
        // Assert 2: 10 days is skipped (outside 7 day window)
        assertTrue(!scheduledIds.contains("s-10"))
        
        // Assert 3: Past is skipped
        assertTrue(!scheduledIds.contains("s-past"))
        
        // Assert: not planned is skipped
        assertTrue(!scheduledIds.contains("s-not-planned"))
        
        // Assert 4: Different sessions get different request codes
        val s3Code = scheduler.scheduled.first { it.first == "s-3" }.third
        val s5Code = scheduler.scheduled.first { it.first == "s-5" }.third
        assertTrue(s3Code != s5Code)
        
        // Assert 5: REARM is scheduled with highest request code
        val rearm = scheduler.scheduled.first { it.first == "REARM" }
        assertEquals(scheduler.scheduled.maxOf { it.third }, rearm.third)
    }
}
