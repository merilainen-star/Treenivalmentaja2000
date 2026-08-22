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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrainingEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrainingRepository
    private lateinit var settingsStore: NotificationSettingsStore
    private lateinit var scheduler: FakeReminderScheduler
    private lateinit var rescheduleAlarmsUseCase: RescheduleAlarmsUseCase
    private lateinit var engine: TrainingEngine

    private val fixedToday: LocalDate = LocalDate.of(2026, 8, 10)
    private val fixedZone: ZoneId = ZoneId.of("Europe/Helsinki")
    private val fixedClock: Clock = Clock.fixed(
        fixedToday.atStartOfDay(fixedZone).toInstant(),
        fixedZone
    )

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = TrainingRepository(db, fixedClock)
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

        // Seed the active plan every session below belongs to.
        db.trainingPlanDao().insert(
            TrainingPlanEntity(
                id = "plan-1",
                name = "Test Plan",
                schemaVersion = 1,
                timeZone = fixedZone.id,
                startDate = fixedToday.minusDays(7).toString(),
                description = null,
                createdAt = fixedClock.millis(),
                contentHash = "test-hash",
                isActive = true
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun markSick_transitionsFutureOpenSessionsToPausedAndReschedulesAlarms() = runBlocking {
        val pastDate = fixedToday.minusDays(2).toString()
        val todayDate = fixedToday.toString()
        val futureDate = fixedToday.plusDays(2).toString()

        insertSession("s1", pastDate, SessionStatus.PLANNED)
        insertSession("s2", todayDate, SessionStatus.PLANNED)
        insertSession("s3", futureDate, SessionStatus.PLANNED)

        // Pre-populate alarms, then forget them so we only observe the post-markSick run.
        rescheduleAlarmsUseCase.execute()
        assertTrue(scheduler.scheduled.isNotEmpty())
        scheduler.scheduled.clear()

        engine.markSick("Test Sickness")

        val sessions = repository.getSessions().associateBy { it.id }
        assertEquals(SessionStatus.PLANNED, sessions["s1"]?.status)
        assertEquals(SessionStatus.PAUSED_DUE_TO_ILLNESS, sessions["s2"]?.status)
        assertEquals(SessionStatus.PAUSED_DUE_TO_ILLNESS, sessions["s3"]?.status)

        // Paused sessions must not hold an alarm any more. (s1 stays PLANNED, and the REARM
        // alarm is always re-armed, so both may legitimately appear.)
        val scheduledIds = scheduler.scheduled.map { it.first }.toSet()
        assertTrue(scheduledIds.none { it == "s2" || it == "s3" })
    }

    @Test
    fun markRecovered_noPausedSessions_doesNothing() = runBlocking {
        insertSession("s1", fixedToday.toString(), SessionStatus.PLANNED)

        engine.markRecovered()

        val sessions = repository.getSessions()
        assertEquals(1, sessions.size)
        assertEquals(SessionStatus.PLANNED, sessions[0].status)
    }

    @Test
    fun markRecovered_reschedulesPausedSessionsGradually() = runBlocking {
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

        // s3 & s4 -> shifted so the third session lands on fixedToday + 3 days
        val s3Rescheduled = activeSessions.find { it.originalSessionId == "s3" }
        assertEquals(fixedToday.plusDays(3).toString(), s3Rescheduled?.scheduledDate)

        val s4Rescheduled = activeSessions.find { it.originalSessionId == "s4" }
        assertEquals(fixedToday.plusDays(4).toString(), s4Rescheduled?.scheduledDate)
    }

    @Test
    fun handleMissedSessions_oneMissedSession_movesToNextRestDay() = runBlocking {
        val yesterday = fixedToday.minusDays(1).toString()
        val todayStr = fixedToday.toString()

        insertSession("s1", yesterday, SessionStatus.PLANNED)
        insertSession("s2", todayStr, SessionStatus.PLANNED)

        val proposal = engine.proposeMissedSessions()
        assertEquals(
            MissedSessionsProposal.MoveOne("s1", fixedToday.minusDays(1), fixedToday.plusDays(1)),
            proposal,
        )
        // Previewing/rejecting is a pure read: the original remains open and unmoved.
        assertEquals(yesterday, repository.getSessions().single { it.id == "s1" }.scheduledDate)

        assertTrue(engine.applyMissedSessions(proposal))

        val activeSessions = repository.getSessions().filter { it.status.isOpen }
        val s1Rescheduled = activeSessions.find { it.originalSessionId == "s1" }
        // Next rest day after fixedToday (which has s2) is fixedToday + 1
        assertEquals(fixedToday.plusDays(1).toString(), s1Rescheduled?.scheduledDate)
    }

    @Test
    fun handleMissedSessions_multipleMissedSessions_shiftsEntirePlanForward() = runBlocking {
        val d1 = fixedToday.minusDays(3).toString()
        val d2 = fixedToday.minusDays(1).toString()

        insertSession("s1", d1, SessionStatus.PLANNED)
        insertSession("s2", d2, SessionStatus.PLANNED)

        val proposal = engine.proposeMissedSessions()
        assertEquals(
            MissedSessionsProposal.ShiftPlan(
                missedSessionIds = listOf("s1", "s2"),
                firstMissedDate = fixedToday.minusDays(3),
                days = 3,
                affectedSessions = 2,
            ),
            proposal,
        )
        assertTrue(engine.applyMissedSessions(proposal))

        val activeSessions = repository.getSessions().filter { it.status.isOpen }
        // Shift amount = fixedToday - d1 = 3 days
        val s1Rescheduled = activeSessions.find { it.originalSessionId == "s1" }
        assertEquals(fixedToday.toString(), s1Rescheduled?.scheduledDate)

        val s2Rescheduled = activeSessions.find { it.originalSessionId == "s2" }
        assertEquals(fixedToday.plusDays(2).toString(), s2Rescheduled?.scheduledDate)

        // The accepted proposal is stale after the first write and cannot shift the plan twice.
        assertEquals(false, engine.applyMissedSessions(proposal))
        assertEquals(2, repository.getSessions().count { it.status.isOpen })
    }

    @Test
    fun proposeMissedSessions_whenNothingWasMissed_proposesNothing() = runBlocking {
        insertSession("s1", fixedToday.toString(), SessionStatus.PLANNED)

        assertEquals(MissedSessionsProposal.None, engine.proposeMissedSessions())
    }

    @Test
    fun missedSessionDateUsesActivePlanZoneWhileTravelling() = runBlocking {
        // 22:30 UTC is already 11 August in Helsinki but still 10 August in Los Angeles.
        val travelClock = Clock.fixed(
            java.time.Instant.parse("2026-08-10T22:30:00Z"),
            ZoneId.of("America/Los_Angeles"),
        )
        val travelEngine = TrainingEngine(repository, travelClock)
        insertSession("s1", "2026-08-10", SessionStatus.PLANNED)

        val proposal = travelEngine.proposeMissedSessions()

        assertTrue(proposal is MissedSessionsProposal.MoveOne)
        assertEquals(LocalDate.of(2026, 8, 10), (proposal as MissedSessionsProposal.MoveOne).fromDate)
    }

    /**
     * A proposal held while the world changed underneath it must not be applied.
     *
     * The twice-accepted case is already covered; this is the other half and the dangerous one. The
     * user is shown "move one session to the next free day", a second session then goes missed
     * — the card was raised on Tuesday and accepted on Thursday, or a background write landed
     * between the two — and the correct action is now a whole-plan shift. Applying the stale
     * `MoveOne` would move one session and quietly leave the other stranded in the past.
     */
    @Test
    fun applyMissedSessions_rejectsAProposalTheSituationHasOutgrown() = runBlocking {
        insertSession("s1", fixedToday.minusDays(1).toString(), SessionStatus.PLANNED)

        val staleProposal = engine.proposeMissedSessions()
        assertTrue(staleProposal is MissedSessionsProposal.MoveOne)

        // A second session goes missed after the card was drawn.
        insertSession("s2", fixedToday.minusDays(2).toString(), SessionStatus.PLANNED)
        assertTrue(engine.proposeMissedSessions() is MissedSessionsProposal.ShiftPlan)

        assertEquals(false, engine.applyMissedSessions(staleProposal))

        // Nothing moved: both are still on the days they were inserted on.
        val byId = repository.getSessions().associateBy { it.id }
        assertEquals(fixedToday.minusDays(1).toString(), byId["s1"]?.scheduledDate)
        assertEquals(fixedToday.minusDays(2).toString(), byId["s2"]?.scheduledDate)
    }

    /**
     * The backlog answered by closing it: the missed sessions end as COMPLETED, on their own dates.
     *
     * This is the case the card had no button for. A plan carrying sessions nobody will ever train
     * — the rows left behind while the app was being built — could only be shifted forward or left
     * alone, and both leave them missed, so the card returned every day.
     */
    @Test
    fun completeMissedSessions_marksThePastOnesDoneAndMovesNothing() = runBlocking {
        val d1 = fixedToday.minusDays(3).toString()
        val d2 = fixedToday.minusDays(1).toString()
        val future = fixedToday.plusDays(1).toString()

        insertSession("s1", d1, SessionStatus.PLANNED)
        insertSession("s2", d2, SessionStatus.NOTIFIED)
        insertSession("s3", future, SessionStatus.PLANNED)

        val proposal = engine.proposeMissedSessions()
        assertEquals(2, engine.completeMissedSessions(proposal))

        val byId = repository.getSessions().associateBy { it.id }
        assertEquals(SessionStatus.COMPLETED, byId["s1"]?.status)
        assertEquals(SessionStatus.COMPLETED, byId["s2"]?.status)
        // Dates untouched: this closes history, it does not rewrite it.
        assertEquals(d1, byId["s1"]?.scheduledDate)
        assertEquals(d2, byId["s2"]?.scheduledDate)
        // The future session is not the user's backlog and must be left alone.
        assertEquals(SessionStatus.PLANNED, byId["s3"]?.status)

        // And the question is now gone for good, which is the entire point.
        assertEquals(MissedSessionsProposal.None, engine.proposeMissedSessions())
    }

    /** Why the sessions reached COMPLETED is recorded, since the status alone would imply training. */
    @Test
    fun completeMissedSessions_writesAnEventSayingItWasMarkedByHand() = runBlocking {
        insertSession("s1", fixedToday.minusDays(1).toString(), SessionStatus.PLANNED)

        assertEquals(1, engine.completeMissedSessions(engine.proposeMissedSessions()))

        val notes = repository.getEvents("s1").mapNotNull { it.note }
        assertTrue(notes.any { it.contains("Merkitty tehdyksi") })
    }

    /** A session paused by illness reaches COMPLETED the long way round, or not at all. */
    @Test
    fun completeMissedSessions_completesASessionPausedByIllness() = runBlocking {
        insertSession("s1", fixedToday.minusDays(2).toString(), SessionStatus.PAUSED_DUE_TO_ILLNESS)
        insertSession("s2", fixedToday.minusDays(1).toString(), SessionStatus.PLANNED)

        assertEquals(2, engine.completeMissedSessions(engine.proposeMissedSessions()))

        val byId = repository.getSessions().associateBy { it.id }
        assertEquals(SessionStatus.COMPLETED, byId["s1"]?.status)
        assertEquals(SessionStatus.COMPLETED, byId["s2"]?.status)
    }

    /** The same staleness guard as accepting: a preview the situation has outgrown writes nothing. */
    @Test
    fun completeMissedSessions_rejectsAStaleProposal() = runBlocking {
        insertSession("s1", fixedToday.minusDays(1).toString(), SessionStatus.PLANNED)
        val staleProposal = engine.proposeMissedSessions()

        insertSession("s2", fixedToday.minusDays(2).toString(), SessionStatus.PLANNED)

        assertEquals(0, engine.completeMissedSessions(staleProposal))
        assertEquals(0, engine.completeMissedSessions(MissedSessionsProposal.None))

        val byId = repository.getSessions().associateBy { it.id }
        assertEquals(SessionStatus.PLANNED, byId["s1"]?.status)
        assertEquals(SessionStatus.PLANNED, byId["s2"]?.status)
    }

    /** `None` is not a proposal. Applying it must be a no-op rather than an empty write. */
    @Test
    fun applyMissedSessions_withNothingProposed_changesNothing() = runBlocking {
        insertSession("s1", fixedToday.toString(), SessionStatus.PLANNED)

        assertEquals(false, engine.applyMissedSessions(MissedSessionsProposal.None))

        assertEquals(fixedToday.toString(), repository.getSessions().single().scheduledDate)
    }

    private suspend fun insertSession(id: String, date: String, status: SessionStatus) {
        val remindAt = ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(18, 0), fixedZone)
            .toInstant()
            .toEpochMilli()
        db.workoutSessionDao().insert(
            WorkoutSessionEntity(
                id = id,
                planId = "plan-1",
                type = WorkoutType.RUNNING,
                weekNumber = 1,
                scheduledDate = date,
                scheduledTime = "18:00",
                remindAtUtc = remindAt,
                timeIsFixed = false,
                reminderOverride = null,
                durationMin = 45,
                description = "Desc",
                status = status,
                appliedLighterVariant = false,
                originalSessionId = null,
                updatedAt = fixedClock.millis()
            )
        )
    }
}
