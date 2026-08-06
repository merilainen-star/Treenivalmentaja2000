package fi.merilainen.treenivalmentaja.data.repository

import androidx.room.withTransaction
import fi.merilainen.treenivalmentaja.data.importer.ImportError
import fi.merilainen.treenivalmentaja.data.importer.ImportResult
import fi.merilainen.treenivalmentaja.data.importer.PlanJson
import fi.merilainen.treenivalmentaja.data.importer.PlanValidator
import fi.merilainen.treenivalmentaja.data.importer.ValidationOutcome
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.SessionEventEntity
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.data.toDomain
import fi.merilainen.treenivalmentaja.data.toEntity
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.SessionEvent
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Why a status change was rejected. */
sealed interface TransitionResult {
  data object Applied : TransitionResult

  data object SessionNotFound : TransitionResult

  data class NotAllowed(val from: SessionStatus, val to: SessionStatus) : TransitionResult
}

/**
 * The only way into the training data. UI and ViewModels never touch a DAO — see `AGENTS.md`.
 *
 * Every accepted status change writes the updated session **and** an immutable `SessionEvent` in
 * one transaction, so the history can never drift from the current state.
 */
class TrainingRepository(
  private val db: AppDatabase,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
  private val planDao = db.trainingPlanDao()
  private val sessionDao = db.workoutSessionDao()
  private val eventDao = db.sessionEventDao()

  private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

  fun observeSessions(): Flow<List<TrainingSession>> =
    sessionDao.observeActivePlanSessions().map { rows -> rows.map { it.toDomain() } }

  fun observeEvents(sessionId: String): Flow<List<SessionEvent>> =
    eventDao.observeForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

  suspend fun getEvents(sessionId: String): List<SessionEvent> =
    eventDao.getForSession(sessionId).map { it.toDomain() }

  suspend fun getSessions(): List<TrainingSession> {
    val planId = planDao.getActivePlanId() ?: return emptyList()
    return sessionDao.getByPlan(planId).map { it.toDomain() }
  }

  suspend fun getSession(id: String): TrainingSession? = sessionDao.getById(id)?.toDomain()

  suspend fun activePlanTimeZone(): ZoneId =
    planDao.getActivePlan()?.let { runCatching { ZoneId.of(it.timeZone) }.getOrNull() }
      ?: clock.zone

  // ---------------------------------------------------------------- status transitions

  /**
   * Moves a session to [target]. Rejects anything the state machine forbids
   * (`docs/TRAINING_ENGINE.md`) and writes nothing in that case.
   */
  suspend fun transition(
    sessionId: String,
    target: SessionStatus,
    source: EventSource = EventSource.USER,
    note: String? = null,
    payloadJson: String? = null,
  ): TransitionResult =
    db.withTransaction {
      val entity = sessionDao.getById(sessionId) ?: return@withTransaction TransitionResult.SessionNotFound
      if (!entity.status.canTransitionTo(target)) {
        return@withTransaction TransitionResult.NotAllowed(entity.status, target)
      }
      val now = clock.millis()
      sessionDao.update(entity.copy(status = target, updatedAt = now))
      eventDao.insert(
        SessionEventEntity(
          id = idGenerator(),
          sessionId = sessionId,
          timestampUtc = now,
          fromStatus = entity.status,
          toStatus = target,
          source = source,
          note = note,
          payloadJson = payloadJson,
        )
      )
      TransitionResult.Applied
    }

  /**
   * Applies the plan's lighter alternative. Falls back to cutting duration and distance by 40%
   * when the plan defines none, as described in `docs/TRAINING_ENGINE.md`.
   */
  suspend fun applyLighterVersion(
    sessionId: String,
    source: EventSource = EventSource.USER,
  ): TransitionResult =
    db.withTransaction {
      val entity = sessionDao.getById(sessionId) ?: return@withTransaction TransitionResult.SessionNotFound
      val target = SessionStatus.REPLACED_WITH_LIGHTER_VERSION
      if (!entity.status.canTransitionTo(target)) {
        return@withTransaction TransitionResult.NotAllowed(entity.status, target)
      }
      val now = clock.millis()
      val lighter = PlanJson.decodeLighter(entity.lighterAlternativeJson)
      val lightened =
        if (lighter != null) {
          entity.copy(
            durationMin = lighter.durationMin ?: entity.durationMin,
            distanceKm = lighter.distanceKm ?: entity.distanceKm,
            intensity = lighter.intensity?.name ?: entity.intensity,
            rounds = lighter.rounds ?: entity.rounds,
            description = lighter.description ?: entity.description,
            exercisesJson =
              PlanJson.encodeExercises(lighter.exercises) ?: entity.exercisesJson,
          )
        } else {
          entity.copy(
            durationMin = entity.durationMin?.let { (it * 0.6).toInt().coerceAtLeast(1) },
            distanceKm = entity.distanceKm?.let { it * 0.6 },
            rounds = entity.rounds?.let { (it - 1).coerceAtLeast(1) },
          )
        }
      sessionDao.update(
        lightened.copy(status = target, appliedLighterVariant = true, updatedAt = now)
      )
      eventDao.insert(
        SessionEventEntity(
          id = idGenerator(),
          sessionId = sessionId,
          timestampUtc = now,
          fromStatus = entity.status,
          toStatus = target,
          source = source,
          note =
            if (lighter != null) "Käytettiin suunnitelman kevyempää vaihtoehtoa"
            else "Kevennetty automaattisesti (suunnitelmassa ei vaihtoehtoa)",
        )
      )
      TransitionResult.Applied
    }

  /**
   * Moves a session to another day. The original row is closed as `RESCHEDULED` and a **new** row
   * is inserted carrying `originalSessionId`; a session's date is never rewritten in place.
   * See `docs/DATA_MODEL.md` § "Rescheduling and the session chain".
   */
  suspend fun reschedule(
    sessionId: String,
    newDate: LocalDate,
    newTime: LocalTime? = null,
    source: EventSource = EventSource.USER,
    note: String? = null,
  ): TransitionResult =
    db.withTransaction {
      val entity = sessionDao.getById(sessionId) ?: return@withTransaction TransitionResult.SessionNotFound
      val target = SessionStatus.RESCHEDULED
      if (!entity.status.canTransitionTo(target)) {
        return@withTransaction TransitionResult.NotAllowed(entity.status, target)
      }
      val now = clock.millis()
      val zone = activePlanTimeZone()
      val time = newTime ?: entity.scheduledTime?.let { LocalTime.parse(it, timeFormat) }
      val newId = "${entity.id}@${idGenerator().take(8)}"

      sessionDao.update(entity.copy(status = target, updatedAt = now))
      sessionDao.insert(
        entity.copy(
          id = newId,
          scheduledDate = newDate.toString(),
          scheduledTime = time?.format(timeFormat),
          remindAtUtc = time?.let { ZonedDateTime.of(newDate, it, zone).toInstant().toEpochMilli() } ?: java.time.ZonedDateTime.of(newDate, java.time.LocalTime.NOON, zone).toInstant().toEpochMilli(),
          status = SessionStatus.PLANNED,
          originalSessionId = entity.id,
          updatedAt = now,
        )
      )
      val payload =
        """{"fromDate":"${entity.scheduledDate}","toDate":"$newDate","newSessionId":"$newId"}"""
      eventDao.insert(
        SessionEventEntity(
          id = idGenerator(),
          sessionId = entity.id,
          timestampUtc = now,
          fromStatus = entity.status,
          toStatus = target,
          source = source,
          note = note ?: "Siirretty päivälle $newDate",
          payloadJson = payload,
        )
      )
      eventDao.insert(
        SessionEventEntity(
          id = idGenerator(),
          sessionId = newId,
          timestampUtc = now,
          fromStatus = null,
          toStatus = SessionStatus.PLANNED,
          source = source,
          note = "Luotu siirrosta (alkuperäinen ${entity.id})",
          payloadJson = payload,
        )
      )
      TransitionResult.Applied
    }

  // ---------------------------------------------------------------- import

  /**
   * Validates [rawJson] against the plan schema and writes it only if the whole document is
   * valid and does not collide with stored data.
   */
  suspend fun importPlan(rawJson: String, activate: Boolean = true): ImportResult {
    val document =
      PlanJson.parse(rawJson).getOrElse { error ->
        return ImportResult.Unreadable(error.message ?: "tuntematon lukuvirhe")
      }

    val validated =
      when (val outcome = PlanValidator.validate(document)) {
        is ValidationOutcome.Errors -> return ImportResult.Invalid(outcome.errors)
        is ValidationOutcome.Valid -> outcome.plan
      }

    val hash = PlanJson.contentHash(rawJson)

    return db.withTransaction {
      val existingPlan = planDao.getById(validated.id)
      if (existingPlan != null) {
        return@withTransaction if (existingPlan.contentHash == hash) {
          ImportResult.AlreadyImported(existingPlan.id, existingPlan.name)
        } else {
          ImportResult.Conflict(planId = validated.id, conflictingSessionIds = emptyList())
        }
      }

      val collidingSessions = sessionDao.existingIds(validated.sessions.map { it.id })
      if (collidingSessions.isNotEmpty()) {
        return@withTransaction ImportResult.Conflict(
          planId = null,
          conflictingSessionIds = collidingSessions.sorted(),
        )
      }

      val now = clock.millis()
      if (activate) planDao.deactivateAll()
      planDao.insert(
        TrainingPlanEntity(
          id = validated.id,
          name = validated.name,
          schemaVersion = validated.schemaVersion,
          timeZone = validated.timeZone,
          startDate = validated.startDate,
          description = validated.description,
          createdAt = now,
          contentHash = hash,
          isActive = activate,
        )
      )
      val entities = validated.sessions.map { it.toEntity(validated.id, now) }
      sessionDao.insertAll(entities)
      eventDao.insertAll(
        entities.map { session ->
          SessionEventEntity(
            id = idGenerator(),
            sessionId = session.id,
            timestampUtc = now,
            fromStatus = null,
            toStatus = SessionStatus.PLANNED,
            source = EventSource.IMPORT,
            note = "Tuotu suunnitelmasta \"${validated.name}\"",
          )
        }
      )
      ImportResult.Success(validated.id, validated.name, entities.size)
    }
  }

  // ---------------------------------------------------------------- seeding

  /**
   * Populates an empty database with the starter week so a fresh install does not open on a blank
   * screen. Does nothing once any plan exists.
   */
  suspend fun seedIfEmpty(): Boolean {
    if (planDao.count() > 0) return false
    val today = LocalDate.now(clock)
    val json = SeedPlan.json(today, clock.zone.id)
    return importPlan(json) is ImportResult.Success
  }

  suspend fun resetSampleData(): Boolean {
    db.withTransaction {
      sessionDao.deleteAll()
      planDao.deleteAll()
      eventDao.deleteAll()
    }
    val today = LocalDate.now(clock)
    val json = SeedPlan.json(today, clock.zone.id)
    return importPlan(json) is ImportResult.Success
  }

  /** Convenience for surfacing validation problems in the UI. */
  fun describeErrors(errors: List<ImportError>): String = errors.joinToString("\n") { it.toString() }
}
