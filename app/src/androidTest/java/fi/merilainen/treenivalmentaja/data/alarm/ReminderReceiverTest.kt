package fi.merilainen.treenivalmentaja.data.alarm

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.TreenivalmentajaApplication
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderReceiverTest {
    private lateinit var app: TreenivalmentajaApplication
    private lateinit var receiver: ReminderReceiver

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        receiver = ReminderReceiver()
        
        // Setup initial data via repository or dao if needed
        // For instrumented test, the DB is real (or in-memory via test application, wait - the real app uses real DB, which might be populated or empty.
        // I will clear it.
        app.db.clearAllTables()
        
        // Add a plan and session
        app.db.trainingPlanDao().insert(
            fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity(
                id = "plan1", name = "Test Plan", schemaVersion = 1,
                timeZone = "Europe/Helsinki", startDate = "2026-08-01",
                description = "Desc", createdAt = 1000, contentHash = "hash", isActive = true
            )
        )
    }

    @Test
    fun receiverTransitionsPlannedSessionToNotifiedAndWritesEvent() = runBlocking {
        // Arrange
        app.db.workoutSessionDao().insert(
            fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity(
                id = "s-1", planId = "plan1", type = WorkoutType.RUNNING, weekNumber = 1,
                scheduledDate = "2026-08-01", scheduledTime = "18:00",
                remindAtUtc = 1690902000000, durationMin = 60, distanceKm = 5.0,
                intensity = "EASY", description = "Run", status = SessionStatus.PLANNED,
                updatedAt = 1000
            )
        )

        val intent = Intent().apply { putExtra("SESSION_ID", "s-1") }

        // Act
        // Because ReminderReceiver uses goAsync and launches a coroutine, we can't easily wait for it without some synchronisation in an instrumented test.
        // Wait, ReminderReceiver doesn't block. We can just call it and wait a bit.
        receiver.onReceive(app, intent)
        
        // Wait for coroutine
        Thread.sleep(1000)

        // Assert
        val session = app.repository.getSession("s-1")
        assertEquals(SessionStatus.NOTIFIED, session?.status)
        
        val events = app.repository.getEvents("s-1")
        val lastEvent = events.last()
        assertEquals(SessionStatus.NOTIFIED, lastEvent.toStatus)
        assertEquals(EventSource.ALARM, lastEvent.source)
    }

    @Test
    fun receiverDoesNothingIfSessionIsCompleted() = runBlocking {
        app.db.workoutSessionDao().insert(
            fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity(
                id = "s-2", planId = "plan1", type = WorkoutType.STRENGTH, weekNumber = 1,
                scheduledDate = "2026-08-01", scheduledTime = "18:00",
                remindAtUtc = 1690902000000, durationMin = 45, distanceKm = null,
                intensity = "HARD", description = "Gym", status = SessionStatus.COMPLETED,
                updatedAt = 1000
            )
        )

        val intent = Intent().apply { putExtra("SESSION_ID", "s-2") }

        receiver.onReceive(app, intent)
        Thread.sleep(500)

        val session = app.repository.getSession("s-2")
        assertEquals(SessionStatus.COMPLETED, session?.status)
    }
}
