package fi.merilainen.treenivalmentaja.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fi.merilainen.treenivalmentaja.data.importer.ImportResult
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.data.repository.TransitionResult
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real Room schema in memory: status transitions, the append-only event log, the
 * reschedule chain, and duplicate detection on import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainingRepositoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: TrainingRepository

  private val zone: ZoneId = ZoneId.of("Europe/Helsinki")
  private val clock: Clock = Clock.fixed(Instant.parse("2026-08-10T05:00:00Z"), zone)
  private val ids = AtomicInteger(0)

  @Before
  fun setUp() {
    db =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AppDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()
    repository = TrainingRepository(db, clock) { "id-${ids.incrementAndGet()}" }
  }

  @After
  fun tearDown() {
    db.close()
  }

  // ------------------------------------------------------------------ import

  @Test
  fun `valid plan is imported and exposed through the flow`() = runTest {
    val result = repository.importPlan(PLAN)
    assertTrue(result is ImportResult.Success)
    result as ImportResult.Success
    assertEquals("plan-testi", result.planId)
    assertEquals(2, result.sessionCount)

    val sessions = repository.observeSessions().first()
    assertEquals(listOf("s-1", "s-2"), sessions.map { it.id })
    assertTrue(sessions.all { it.status == SessionStatus.PLANNED })
  }

  @Test
  fun `import writes one creation event per session`() = runTest {
    repository.importPlan(PLAN)

    val events = repository.getEvents("s-1")
    assertEquals(1, events.size)
    assertNull("a creation event has no previous status", events.single().fromStatus)
    assertEquals(SessionStatus.PLANNED, events.single().toStatus)
    assertEquals(EventSource.IMPORT, events.single().source)
  }

  @Test
  fun `re-importing the identical document is reported as already imported`() = runTest {
    assertTrue(repository.importPlan(PLAN) is ImportResult.Success)

    val second = repository.importPlan(PLAN)
    assertTrue(second is ImportResult.AlreadyImported)
    assertEquals("plan-testi", (second as ImportResult.AlreadyImported).planId)

    // Nothing was written a second time.
    assertEquals(2, db.workoutSessionDao().count())
    assertEquals(1, repository.getEvents("s-1").size)
  }

  @Test
  fun `re-importing the same plan id with different content is a conflict`() = runTest {
    repository.importPlan(PLAN)

    val edited = PLAN.replace("\"durationMin\": 45", "\"durationMin\": 60")
    val result = repository.importPlan(edited)

    assertTrue(result is ImportResult.Conflict)
    assertEquals("plan-testi", (result as ImportResult.Conflict).planId)
    // The stored session keeps its original duration — nothing was overwritten.
    assertEquals(45, repository.getSession("s-1")?.durationMin)
  }

  /**
   * This used to be a conflict, and the change is deliberate. An activated import now deletes the
   * plan it replaces, so by the time session ids are checked the old ones are gone and there is
   * nothing to collide with. Reusing ids across successive plans — the same programme re-exported,
   * say — is therefore ordinary rather than an error the user has to clear by hand.
   */
  @Test
  fun `a new plan reusing session ids replaces the old one instead of conflicting`() = runTest {
    repository.importPlan(PLAN)

    val other = PLAN.replace("\"id\": \"plan-testi\"", "\"id\": \"plan-toinen\"")
    val result = repository.importPlan(other)

    assertTrue("expected success, was $result", result is ImportResult.Success)
    assertEquals(listOf("s-1", "s-2"), repository.getSessions().map { it.id }.sorted())
    assertEquals(1, db.trainingPlanDao().count())
  }

  /** An import that does not activate keeps what is there, so ids must still not clash. */
  @Test
  fun `an inactive import reusing session ids is still a conflict`() = runTest {
    repository.importPlan(PLAN)

    val other = PLAN.replace("\"id\": \"plan-testi\"", "\"id\": \"plan-toinen\"")
    val result = repository.importPlan(other, activate = false)

    assertTrue(result is ImportResult.Conflict)
    result as ImportResult.Conflict
    assertNull(result.planId)
    assertEquals(listOf("s-1", "s-2"), result.conflictingSessionIds)
  }

  @Test
  fun `an invalid document writes nothing`() = runTest {
    val result = repository.importPlan(PLAN.replace("\"time\": \"07:00\"", "\"time\": \"25:00\""))

    assertTrue(result is ImportResult.Invalid)
    assertTrue((result as ImportResult.Invalid).errors.isNotEmpty())
    assertEquals(0, db.trainingPlanDao().count())
    assertEquals(0, db.workoutSessionDao().count())
  }

  @Test
  fun `unreadable text writes nothing`() = runTest {
    val result = repository.importPlan("{ ei ole jsonia")
    assertTrue(result is ImportResult.Unreadable)
    assertEquals(0, db.trainingPlanDao().count())
  }

  // ------------------------------------------------------------------ transitions & history

  @Test
  fun `event history accumulates one row per accepted transition`() = runTest {
    repository.importPlan(PLAN)

    assertEquals(TransitionResult.Applied, repository.transition("s-1", SessionStatus.NOTIFIED, EventSource.ALARM))
    assertEquals(TransitionResult.Applied, repository.applyLighterVersion("s-1"))
    assertEquals(TransitionResult.Applied, repository.transition("s-1", SessionStatus.STARTED))
    assertEquals(TransitionResult.Applied, repository.transition("s-1", SessionStatus.COMPLETED))

    val events = repository.getEvents("s-1")
    assertEquals(5, events.size)
    assertEquals(
      listOf(
        null to SessionStatus.PLANNED,
        SessionStatus.PLANNED to SessionStatus.NOTIFIED,
        SessionStatus.NOTIFIED to SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION to SessionStatus.STARTED,
        SessionStatus.STARTED to SessionStatus.COMPLETED,
      ),
      events.map { it.fromStatus to it.toStatus },
    )
    assertEquals(EventSource.ALARM, events[1].source)

    val session = repository.getSession("s-1")!!
    assertEquals(SessionStatus.COMPLETED, session.status)
    assertTrue(
      "\"completed, but lighter\" must survive the later transition",
      session.appliedLighterVariant,
    )
  }

  @Test
  fun `a forbidden transition changes nothing and appends no event`() = runTest {
    repository.importPlan(PLAN)
    repository.transition("s-1", SessionStatus.COMPLETED)
    val eventsBefore = repository.getEvents("s-1").size

    val result = repository.transition("s-1", SessionStatus.PLANNED)

    assertEquals(TransitionResult.NotAllowed(SessionStatus.COMPLETED, SessionStatus.PLANNED), result)
    assertEquals(SessionStatus.COMPLETED, repository.getSession("s-1")?.status)
    assertEquals(eventsBefore, repository.getEvents("s-1").size)
  }

  @Test
  fun `transitioning an unknown session is reported, not crashed`() = runTest {
    repository.importPlan(PLAN)
    assertEquals(
      TransitionResult.SessionNotFound,
      repository.transition("ei-ole", SessionStatus.COMPLETED),
    )
  }

  @Test
  fun `lighter version applies the plan payload`() = runTest {
    repository.importPlan(PLAN)

    repository.applyLighterVersion("s-2")

    val session = repository.getSession("s-2")!!
    assertEquals(SessionStatus.REPLACED_WITH_LIGHTER_VERSION, session.status)
    assertTrue(session.appliedLighterVariant)
    assertEquals(25, session.durationMin)
    assertEquals("Lyhyt palauttava lenkki.", session.description)
  }

  @Test
  fun `lighter version falls back to a reduction when the plan offers none`() = runTest {
    repository.importPlan(PLAN)

    repository.applyLighterVersion("s-1")

    val session = repository.getSession("s-1")!!
    assertEquals(SessionStatus.REPLACED_WITH_LIGHTER_VERSION, session.status)
    assertEquals(27, session.durationMin) // 45 * 0.6
  }

  // ------------------------------------------------------------------ rescheduling

  @Test
  fun `rescheduling closes the old row and links a new one`() = runTest {
    repository.importPlan(PLAN)

    val oldSession = db.workoutSessionDao().getById("s-1")!!
    val result = repository.reschedule("s-1", LocalDate.parse("2026-08-12"))
    assertEquals(TransitionResult.Applied, result)

    val original = repository.getSession("s-1")!!
    assertEquals(SessionStatus.RESCHEDULED, original.status)
    assertEquals("the original date is never rewritten", "2026-08-10", original.scheduledDate)

    val sessions = repository.observeSessions().first()
    val moved = sessions.single { it.originalSessionId == "s-1" }
    assertEquals(SessionStatus.PLANNED, moved.status)
    assertEquals("2026-08-12", moved.scheduledDate)
    assertEquals("07:00", moved.scheduledTime)
    assertEquals(original.type, moved.type)

    // The closed row is not shown as work any more, but it is still queryable.
    assertTrue(sessions.none { it.id == "s-1" && it.status != SessionStatus.RESCHEDULED })
  }

  @Test
  fun `rescheduling records the move on both rows`() = runTest {
    repository.importPlan(PLAN)
    repository.reschedule("s-1", LocalDate.parse("2026-08-12"))

    val originalEvents = repository.getEvents("s-1")
    assertEquals(2, originalEvents.size)
    assertEquals(SessionStatus.RESCHEDULED, originalEvents.last().toStatus)
    assertNotNull(originalEvents.last().payloadJson)
    assertTrue(originalEvents.last().payloadJson!!.contains("2026-08-12"))

    val moved = repository.observeSessions().first().single { it.originalSessionId == "s-1" }
    val movedEvents = repository.getEvents(moved.id)
    assertEquals(1, movedEvents.size)
    assertNull(movedEvents.single().fromStatus)
    assertEquals(SessionStatus.PLANNED, movedEvents.single().toStatus)
  }

  @Test
  fun `a rescheduled row cannot be moved again`() = runTest {
    repository.importPlan(PLAN)
    repository.reschedule("s-1", LocalDate.parse("2026-08-12"))

    val second = repository.reschedule("s-1", LocalDate.parse("2026-08-13"))

    assertEquals(
      TransitionResult.NotAllowed(SessionStatus.RESCHEDULED, SessionStatus.RESCHEDULED),
      second,
    )
    // 2 imported + 1 created by the first move; the rejected second move added nothing.
    assertEquals(3, db.workoutSessionDao().count())
  }

  // ------------------------------------------------------------------ seeding

  @Test
  fun `seeding fills an empty database and is not repeated`() = runTest {
    assertTrue(repository.seedIfEmpty())

    val sessions = repository.observeSessions().first()
    assertEquals(8, sessions.size)
    assertTrue(sessions.all { it.status == SessionStatus.PLANNED })
    // Every seeded session carries a creation event, like any imported one.
    assertEquals(1, repository.getEvents(sessions.first().id).size)

    assertFalse("a second call must be a no-op", repository.seedIfEmpty())
    assertEquals(8, db.workoutSessionDao().count())
  }

  @Test
  fun `seeding does not overwrite an imported plan`() = runTest {
    repository.importPlan(PLAN)
    assertFalse(repository.seedIfEmpty())
    assertEquals(2, db.workoutSessionDao().count())
  }

  // ------------------------------------------------------------------ cascade

  @Test
  fun `deleting a plan removes its sessions and their history`() = runTest {
    repository.importPlan(PLAN)
    repository.transition("s-1", SessionStatus.COMPLETED)

    db.trainingPlanDao().deleteById("plan-testi")

    assertEquals(0, db.workoutSessionDao().count())
    assertEquals(0, db.sessionEventDao().countForSession("s-1"))
  }

  // ---------------------------------------------------------------- replacing a plan

  /** A second plan, distinct in every id so nothing can pass by accident. */
  private val secondPlan
    get() = PLAN.replace("plan-testi", "plan-toinen").replace("\"s-", "\"t-")

  /**
   * Importing used to deactivate the previous plan and leave its rows behind: invisible in the
   * UI, growing the database with every import, and still holding alarms — which is how a
   * replaced programme carried on notifying. The rows go now.
   */
  @Test
  fun `importing a plan deletes the one it replaces`() = runTest {
    assertTrue(repository.importPlan(PLAN) is ImportResult.Success)
    assertEquals(2, repository.getSessions().size)

    assertTrue(repository.importPlan(secondPlan) is ImportResult.Success)

    // getSessions only reads the active plan, so the count is checked against the table itself.
    val allIds = db.workoutSessionDao().getByStatus(SessionStatus.PLANNED).map { it.id }
    assertEquals(listOf("t-1", "t-2"), allIds.sorted())
    assertEquals(1, db.trainingPlanDao().count())
  }

  /** The cascade has to reach the event log too, or the history outlives its sessions. */
  @Test
  fun `replacing a plan takes its session history with it`() = runTest {
    repository.importPlan(PLAN)
    repository.transition("s-1", SessionStatus.COMPLETED)
    assertTrue(repository.getEvents("s-1").isNotEmpty())

    repository.importPlan(secondPlan)

    assertNull(repository.getSession("s-1"))
    assertTrue(repository.getEvents("s-1").isEmpty())
  }

  /**
   * Phones that imported before plans were deleted still carry the leftovers, so the cleanup runs
   * at startup too. It must remove exactly the replaced plans and nothing the user can see.
   */
  @Test
  fun `deleteReplacedPlans removes the leftovers and keeps the active plan`() = runTest {
    repository.importPlan(PLAN)
    // A leftover of the shape older builds produced: deactivated, rows still present.
    db.trainingPlanDao().insert(
      TrainingPlanEntity(
        id = "plan-vanha", name = "Vanha", schemaVersion = 1, timeZone = "Europe/Helsinki",
        startDate = "2026-07-01", description = null, createdAt = 1, contentHash = "x",
        isActive = false,
      )
    )
    assertEquals(2, db.trainingPlanDao().count())

    val removed = repository.deleteReplacedPlans()

    assertEquals(1, removed)
    assertEquals(1, db.trainingPlanDao().count())
    assertEquals(listOf("s-1", "s-2"), repository.getSessions().map { it.id }.sorted())
  }

  /** Nothing to clean up is not an error, and must not touch the plan in use. */
  @Test
  fun `deleteReplacedPlans leaves a lone active plan alone`() = runTest {
    repository.importPlan(PLAN)

    assertEquals(0, repository.deleteReplacedPlans())
    assertEquals(2, repository.getSessions().size)
  }

  // ---------------------------------------------------------------- import start date

  /**
   * A plan file's dates are the coach's calendar, not necessarily the athlete's. Importing with
   * `startToday` moves the whole thing so day one is today, which is a different question from
   * "did the plan validate" and is therefore worth pinning separately.
   */
  @Test
  fun `startToday moves the plan to today and keeps the gaps`() = runTest {
    // Same plan, written a fortnight before the fixed clock's 2026-08-10.
    val past = PLAN.replace("2026-08-10", "2026-07-27").replace("2026-08-11", "2026-07-28")

    assertTrue(repository.importPlan(past, startToday = true) is ImportResult.Success)

    val dates = repository.getSessions().map { it.scheduledDate }.sorted()
    assertEquals(listOf("2026-08-10", "2026-08-11"), dates)
  }

  /** Without the flag the document is taken at its word, wherever that falls. */
  @Test
  fun `by default the file's own dates are used`() = runTest {
    val past = PLAN.replace("2026-08-10", "2026-07-27").replace("2026-08-11", "2026-07-28")

    assertTrue(repository.importPlan(past) is ImportResult.Success)

    val dates = repository.getSessions().map { it.scheduledDate }.sorted()
    assertEquals(listOf("2026-07-27", "2026-07-28"), dates)
  }

  /**
   * The reminder has to be recomputed from the new date, not shifted by a fixed number of
   * milliseconds: 27 July is inside summer time and a plan shifted across the October change
   * would otherwise fire an hour out for every session on the far side.
   */
  @Test
  fun `startToday recomputes the reminder for the new date`() = runTest {
    val past = PLAN.replace("2026-08-10", "2026-07-27").replace("2026-08-11", "2026-07-28")

    repository.importPlan(past, startToday = true)

    val session = repository.getSessions().single { it.scheduledDate == "2026-08-10" }
    val expected =
      ZonedDateTime.of(LocalDate.of(2026, 8, 10), LocalTime.of(7, 0), zone).toInstant().toEpochMilli()
    assertEquals(expected, session.remindAtUtc)
  }

  /** Nothing to move when the plan already starts today; the rows must come through untouched. */
  @Test
  fun `startToday on a plan that already starts today changes nothing`() = runTest {
    assertTrue(repository.importPlan(PLAN, startToday = true) is ImportResult.Success)

    val dates = repository.getSessions().map { it.scheduledDate }.sorted()
    assertEquals(listOf("2026-08-10", "2026-08-11"), dates)
  }

  private companion object {
    const val PLAN =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-testi",
          "name": "Testisuunnitelma",
          "timeZone": "Europe/Helsinki",
          "startDate": "2026-08-10"
        },
        "weeks": [
          {
            "weekNumber": 1,
            "sessions": [
              {
                "id": "s-1",
                "type": "STRENGTH",
                "date": "2026-08-10",
                "time": "07:00",
                "durationMin": 45,
                "description": "Aamun keskivartalo."
              },
              {
                "id": "s-2",
                "type": "RUNNING",
                "date": "2026-08-11",
                "time": "16:30",
                "durationMin": 45,
                "distanceKm": 5.0,
                "lighterAlternative": {
                  "durationMin": 25,
                  "intensity": "EASY",
                  "description": "Lyhyt palauttava lenkki."
                }
              }
            ]
          }
        ]
      }
      """
  }

@Test
  fun `rescheduling a session without time preserves null time without crashing`() = runTest {
    val importRes = repository.importPlan(PLAN.replace("\"07:00\"", "null")) // make s-1 time null
    assertTrue("Import failed: $importRes", importRes is fi.merilainen.treenivalmentaja.data.importer.ImportResult.Success)
    val oldSession = db.workoutSessionDao().getById("s-1")!!
    val result = repository.reschedule("s-1", LocalDate.parse("2026-08-11"), null, EventSource.USER, null)
    assertTrue("Reschedule should return Applied but got " + result.javaClass.simpleName, result is TransitionResult.Applied)
    
    val allSessions = db.workoutSessionDao().getByStatus(SessionStatus.PLANNED)
    val newSession = allSessions.find { it.originalSessionId == "s-1" }!!
    assertNull("Scheduled time should remain null", newSession.scheduledTime)
    assertEquals("2026-08-11", newSession.scheduledDate)
    assertTrue("remindAtUtc should be updated to a newer timestamp", newSession.remindAtUtc > oldSession.remindAtUtc)
  }}
