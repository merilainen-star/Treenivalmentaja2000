package fi.merilainen.treenivalmentaja.data.alarm

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderReceiverNoPermissionTest {
    private lateinit var app: TreenivalmentajaApplication
    private lateinit var receiver: ReminderReceiver

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        receiver = ReminderReceiver()
        
        app.db.clearAllTables()
        
        app.db.trainingPlanDao().insert(
            fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity(
                id = "plan1", name = "Test Plan", schemaVersion = 1,
                timeZone = "Europe/Helsinki", startDate = "2026-08-01",
                description = "Desc", createdAt = 1000, contentHash = "hash", isActive = true
            )
        )
    }

    @Test
    fun receiverLeavesSessionPlannedIfNoNotificationPermission() = runBlocking {
        app.db.workoutSessionDao().insert(
            fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity(
                id = "s-no-perm", planId = "plan1", type = WorkoutType.RUNNING, weekNumber = 1,
                scheduledDate = "2026-08-01", scheduledTime = "18:00",
                remindAtUtc = 1690902000000, durationMin = 60, distanceKm = 5.0,
                intensity = "EASY", description = "Run", status = SessionStatus.PLANNED,
                updatedAt = 1000
            )
        )
        
        val intent = Intent().apply { putExtra("SESSION_ID", "s-no-perm") }
        
        receiver.onReceive(app, intent)
        
        Thread.sleep(1000)
        
        val session = app.repository.getSession("s-no-perm")
        assertEquals(SessionStatus.PLANNED, session?.status)
        
        val events = app.repository.getEvents("s-no-perm")
        assertTrue("No events should be logged when notification is not shown", events.isEmpty())
    }
}
