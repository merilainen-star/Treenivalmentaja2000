package fi.merilainen.treenivalmentaja.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrainingEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrainingRepository
    private lateinit var settingsStore: NotificationSettingsStore
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var rescheduleAlarmsUseCase: RescheduleAlarmsUseCase
    private lateinit var engine: TrainingEngine

    private val fixedToday = LocalDate.of(2026, 8, 10)
    private val fixedZone = ZoneId.of("Europe/Helsinki")
    private val fixedClock = Clock.fixed(
        fixedToday.atStartOfDay(fixedZone).toInstant(),
        fixedZone
    )

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = TrainingRepository(db)
        settingsStore = NotificationSettingsStore(context)
        scheduler = FakeReminderScheduler(context)

        rescheduleAlarmsUseCase = RescheduleAlarmsUseCase(
            database = db,
            planDao = db.trainingPlanDao(),
            sessionDao = db.workoutSessionDao(),
            settingsStore = settingsStore,
            resolveReminderUseCase = ResolveReminderUseCase(),
            reminderScheduler = scheduler
        )

        engine = TrainingEngine(
            repository = repository,
            clock = fixedClock,
            rescheduleAlarmsUseCase = rescheduleAlarmsUseCase
        )

        // Seed a plan
        db.trainingPlanDao().insert(
            TrainingPlanEntity(
                id = "plan-1",
                name = "Test Plan",
                source = "TEST",
                importedAtUtc = System.currentTimeMillis(),
                timeZone = "Europe/Helsinki"
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun markSick_transitionsFutureOpenSessionsToPausedAndReschedulesAlarms() = runTest {
        val pastDate = fixedToday.minusDays(2).toString()
        val todayDate = fixedToday.toString()
        val futureDate = fixedToday.plusDays(2).toString()

        insertSession("s1", pastDate, SessionStatus.PLANNED)
        insertSession("s2", todayDate, SessionStatus.PLANNED)
        insertSession("s3", futureDate, SessionStatus.PLANNED)

        // Pre-populate alarms
        rescheduleAlarmsUseCase.execute()
        assertTrue(scheduler.scheduled.isNotEmpty())

        engine.markSick("Test Sickness")

        val sessions = repository.getSessions().associateBy { it.id }
        assertEquals(SessionStatus.PLANNED, sessions["s1"]?.status)
        assertEquals(SessionStatus.PAUSED_DUE_TO_ILLNESS, sessions["s2"]?.status)
        assertEquals(SessionStatus.PAUSED_DUE_TO_ILLNESS, sessions["s3"]?.status)

        // Verify active workout alarms are empty (only REARM may exist if window covers it)
        val sessionAlarms = scheduler.scheduled.filter { it.first != "REARM" }
        assertTrue(sessionAlarms.isEmpty())
    }

    @Test
    fun markRecovered_noPausedSessions_doesNothing() = runTest {
        insertSession("s1", fixedToday.toString(), SessionStatus.PLANNED)

        engine.markRecovered()

        val sessions = repository.getSessions()
        assertEquals(1, sessions.size)
        assertEquals(SessionStatus.PLANNED, sessions[0].status)
    }

    @Test
    fun markRecovered_reschedulesPausedSessionsGradually() = runTest {
        val d1 = fixedToday.minusDays(4).toString()
        val d2 = fixedToday.minusDays(3).toString()
        val d3 = fixedToday.minusDays(1).toString()
        val d4 = fixedToday.toString()

        insertSession("s1", d1, SessionStatus.PAUSED_DUE_TO_ILLNESS)
        insertSession("s2", d2, SessionStatus.PAUSED_DUE_TO_ILLNESS)
        insertSession("s3", d3, SessionStatus.PAUSED_DUE_TO_ILLNESS)
        insertSession("s4", d4, SessionStatus.PAUSED_DUE_TO_ILLNESS)

        engine.markRecovered()

        val activeSessions = repository.getSessions().filter { it.status.isOpen }
        assertEquals(4, activeSessions.size)

        // s1 -> rescheduled to fixedToday (Day 1)
        val s1Rescheduled = activeSessions.find { it.originalSessionId == "s1" }
        assertEquals(fixedToday.toString(), s1Rescheduled?.scheduledDate)
        assertTrue(s1Rescheduled?.appliedLighterVariant == true)

        // s2 -> rescheduled to fixedToday + 2 days (Day 3)
        val s2Rescheduled = activeSessions.find { it.originalSessionId == "s2" }
        assertEquals(fixedToday.plusDays(2).toString(), s2Rescheduled?.scheduledDate)
        assertTrue(s2Rescheduled?.appliedLighterVariant == true)

        // s3 & s4 -> shifted starting from fixedToday + 3 days
        val s3Rescheduled = activeSessions.find { it.originalSessionId == "s3" }
        assertEquals(fixedToday.plusDays(3).toString(), s3Rescheduled?.scheduledDate)
    }

    @Test
    fun handleMissedSessions_oneMissedSession_movesToNextRestDay() = runTest {
        val yesterday = fixedToday.minusDays(1).toString()
        val todayStr = fixedToday.toString()

        insertSession("s1", yesterday, SessionStatus.PLANNED)
        insertSession("s2", todayStr, SessionStatus.PLANNED)

        engine.handleMissedSessions()

        val activeSessions = repository.getSessions().filter { it.status.isOpen }
        val s1Rescheduled = activeSessions.find { it.originalSessionId == "s1" }
        // Next rest day after fixedToday (which has s2) is fixedToday + 1
        assertEquals(fixedToday.plusDays(1).toString(), s1Rescheduled?.scheduledDate)
    }

    @Test
    fun handleMissedSessions_multipleMissedSessions_shiftsEntirePlanForward() = runTest {
        val d1 = fixedToday.minusDays(3).toString()
        val d2 = fixedToday.minusDays(1).toString()

        insertSession("s1", d1, SessionStatus.PLANNED)
        insertSession("s2", d2, SessionStatus.PLANNED)

        engine.handleMissedSessions()

        val activeSessions = repository.getSessions().filter { it.status.isOpen }
        // Shift amount = fixedToday - d1 = 3 days
        val s1Rescheduled = activeSessions.find { it.originalSessionId == "s1" }
        assertEquals(fixedToday.toString(), s1Rescheduled?.scheduledDate)

        val s2Rescheduled = activeSessions.find { it.originalSessionId == "s2" }
        assertEquals(fixedToday.plusDays(2).toString(), s2Rescheduled?.scheduledDate)
    }

    private suspend fun insertSession(id: String, date: String, status: SessionStatus) {
        db.workoutSessionDao().insert(
            WorkoutSessionEntity(
                id = id,
                planId = "plan-1",
                type = WorkoutType.RUNNING,
                scheduledDate = date,
                scheduledTime = "18:00",
                durationMin = 45,
                title = "Session $id",
                description = "Desc",
                status = status,
                remindAtUtc = null,
                timeIsFixed = false,
                reminderOverride = null,
                appliedLighterVariant = false,
                originalSessionId = null,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
