package fi.merilainen.treenivalmentaja.data.repository

import androidx.room.withTransaction
import fi.merilainen.treenivalmentaja.data.SessionPayloadJson
import fi.merilainen.treenivalmentaja.data.importer.ImportError
import fi.merilainen.treenivalmentaja.data.importer.ImportResult
import fi.merilainen.treenivalmentaja.data.importer.PendingImport
import fi.merilainen.treenivalmentaja.data.importer.PlanJson
import fi.merilainen.treenivalmentaja.data.importer.PlanValidator
import fi.merilainen.treenivalmentaja.data.importer.ValidatedPlan
import fi.merilainen.treenivalmentaja.data.importer.ValidationOutcome
import fi.merilainen.treenivalmentaja.data.local.AppDatabase
import fi.merilainen.treenivalmentaja.data.local.entity.SessionEventEntity
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity
import fi.merilainen.treenivalmentaja.data.toDomain
import fi.merilainen.treenivalmentaja.data.toEntity
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

  /** The calendar zone of the plan currently shown, changing when an import activates another. */
  fun observeActivePlanTimeZone(): Flow<ZoneId> =
    planDao
      .observeActivePlan()
      .map { plan ->
        plan?.let { runCatching { ZoneId.of(it.timeZone) }.getOrNull() } ?: clock.zone
      }
      .distinctUntilChanged()

  fun observeEvents(sessionId: String): Flow<List<SessionEvent>> =
    eventDao.observeForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

  suspend fun getEvents(sessionId: String): List<SessionEvent> =
    eventDao.getForSession(sessionId).map { it.toDomain() }

  suspend fun getSessions(): List<TrainingSession> {
    val planId = planDao.getActivePlanId() ?: return emptyList()
    return sessionDao.getByPlan(planId).map { it.toDomain() }
  }

  suspend fun getSession(id: String): TrainingSession? = sessionDao.getById(id)?.toDomain()

  /**
   * True when [session] belongs to the plan currently in use.
   *
   * Importing a plan deactivates the previous one instead of deleting it, so a session can be
   * perfectly valid and still belong to a programme the user has replaced.
   */
  suspend fun isInActivePlan(session: TrainingSession): Boolean =
    planDao.getActivePlanId() == session.planId

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
   * Completes a guided strength session, recording how much of it was ticked off.
   *
   * A plain [transition] to `COMPLETED` says the person pressed the button and nothing else, which
   * is what every completed strength session used to say. [progress] is the rest of the answer:
   * the movements they walked through on the way there. It rides on the completion event rather
   * than on the session row because it describes **that act of finishing**, not the plan — and the
   * events table is append-only, so the record cannot later be quietly rewritten.
   *
   * `null` progress falls through to an ordinary completion. A session finished from a screen with
   * no guided list has nothing to report, and inventing a zero for it would say the workout was
   * abandoned.
   */
  suspend fun completeGuided(
    sessionId: String,
    progress: GuidedProgress?,
    source: EventSource = EventSource.USER,
  ): TransitionResult =
    transition(
      sessionId = sessionId,
      target = SessionStatus.COMPLETED,
      source = source,
      payloadJson = progress?.let(SessionPayloadJson::encodeGuidedProgress),
    )

  /**
   * What the guided workout recorded when this session was completed, or `null` if it recorded
   * nothing.
   *
   * Read from the **last** completion carrying such a payload, searched newest first: a session can
   * accumulate several events, and only one of them is the one that finished it. Rows written by
   * anything else — a reschedule's payload, a completion from before this was recorded — decode to
   * `null` and are passed over rather than misread.
   */
  suspend fun guidedProgressFor(sessionId: String): GuidedProgress? =
    eventDao
      .getForSession(sessionId)
      .asReversed()
      .firstNotNullOfOrNull { event ->
        if (event.toStatus != SessionStatus.COMPLETED) null
        else SessionPayloadJson.decodeGuidedProgress(event.payloadJson)
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
  /**
   * @param confirmed the user has seen what this import would do to the plan already stored and
   *   said yes. Without it, anything that would change or discard stored rows returns
   *   [ImportResult.NeedsConfirmation] and writes nothing.
   */
  suspend fun importPlan(
    rawJson: String,
    activate: Boolean = true,
    startToday: Boolean = false,
    confirmed: Boolean = false,
  ): ImportResult {
    val document =
      PlanJson.parse(rawJson).getOrElse { error ->
        return ImportResult.Unreadable(error.message ?: "tuntematon lukuvirhe")
      }

    val validatedAsWritten =
      when (val outcome = PlanValidator.validate(document)) {
        is ValidationOutcome.Errors -> return ImportResult.Invalid(outcome.errors)
        is ValidationOutcome.Valid -> outcome.plan
      }

    val validated =
      if (startToday) shiftToStartToday(validatedAsWritten) else validatedAsWritten

    val hash = PlanJson.contentHash(rawJson)

    return db.withTransaction {
      val now = clock.millis()
      val samePlan = planDao.getById(validated.id)
      if (samePlan != null && samePlan.contentHash == hash) {
        return@withTransaction ImportResult.AlreadyImported(samePlan.id, samePlan.name)
      }

      // What would this import do to what is already here? Nothing is written until the user has
      // been told, because both answers below cost something: one rewrites rows, the other
      // deletes them.
      val pending = planFor(validated, samePlan, activate)
      if (pending != null && !confirmed) {
        return@withTransaction ImportResult.NeedsConfirmation(validated.name, pending)
      }

      if (pending is PendingImport.Update) {
        return@withTransaction updateInPlace(samePlan!!, validated, hash, now)
      }

      // Activating a plan replaces the previous one outright: the old rows are deleted, not just
      // marked inactive. They were dead weight in every sense — invisible in the UI, growing the
      // database with each import, and still owning AlarmManager slots, which is how a replaced
      // programme kept sending its own reminders.
      //
      // Deleting the plan row is enough: workout_sessions cascades from training_plans and
      // session_events cascades from workout_sessions.
      if (activate) planDao.deleteAll()

      // Only reachable when a plan was kept — an inactive import, since an active one just
      // cleared the table. Two plans must never share a session id.
      val collidingSessions = sessionDao.existingIds(validated.sessions.map { it.id })
      if (collidingSessions.isNotEmpty()) {
        return@withTransaction ImportResult.Conflict(
          planId = null,
          conflictingSessionIds = collidingSessions.sorted(),
        )
      }

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

  /**
   * What this import would cost, or `null` when it costs nothing.
   *
   * An import into an empty database, or one that is not being activated, takes nothing away and
   * needs no permission. Everything else does.
   */
  private suspend fun planFor(
    validated: ValidatedPlan,
    samePlan: TrainingPlanEntity?,
    activate: Boolean,
  ): PendingImport? {
    if (!activate) return null

    if (samePlan != null) {
      val stored = sessionDao.getByPlan(samePlan.id)
      // A session the engine moved lives in a row of its own, generated rather than imported. It
      // is not missing from the document — it hangs off one that is there.
      val fromDocument = stored.filter { it.originalSessionId == null }
      val incoming = validated.sessions.associateBy { it.id }
      val dropped = fromDocument.count { it.id !in incoming }
      if (dropped > 0) return replacing(samePlan, stored)

      val byId = stored.associateBy { it.id }
      var changed = 0
      var added = 0
      for (session in validated.sessions) {
        val old = byId[session.id]
        if (old == null) added++
        else if (contentDiffers(old, merge(old, session.toEntity(samePlan.id, old.updatedAt)))) changed++
      }
      return PendingImport.Update(changed = changed, added = added)
    }

    if (planDao.count() == 0) return null
    val current = planDao.getActivePlan()
    return replacing(current, current?.let { sessionDao.getByPlan(it.id) }.orEmpty())
  }

  private fun replacing(plan: TrainingPlanEntity?, stored: List<WorkoutSessionEntity>) =
    PendingImport.Replace(
      replacedPlanName = plan?.name ?: "aiempi suunnitelma",
      recordedSessions = stored.count { it.status != SessionStatus.PLANNED },
    )

  /**
   * The incoming session as it would be stored, keeping everything the document has no opinion
   * about.
   *
   * A plan file describes the training. It says nothing about whether you did it, whether you
   * took the lighter version, which session this one was moved from, or what reminder time you
   * set for it — so a corrected file must not overwrite any of that.
   */
  private fun merge(old: WorkoutSessionEntity, fresh: WorkoutSessionEntity) =
    fresh.copy(
      status = old.status,
      appliedLighterVariant = old.appliedLighterVariant,
      originalSessionId = old.originalSessionId,
      reminderOverride = old.reminderOverride,
      updatedAt = old.updatedAt,
    )

  /**
   * Whether the document actually says something different about this session.
   *
   * `remindAtUtc` is excluded because it is derived, not authored: the validator computes a naive
   * one from the file's date and time, and `RescheduleAlarmsUseCase` immediately recomputes it
   * from the user's notification settings. Comparing it would report every session in the plan as
   * changed on every re-import — 80 of 80 for an eight-week programme in which one description
   * was corrected — which is not information, it is noise with a number on it. A real change to
   * the date or the time still shows up, in `scheduledDate` and `scheduledTime`.
   */
  private fun contentDiffers(old: WorkoutSessionEntity, merged: WorkoutSessionEntity) =
    merged.copy(remindAtUtc = old.remindAtUtc) != old

  /**
   * Corrects a plan without taking it apart.
   *
   * Nothing is deleted, so nothing cascades: every session keeps its id, and with it its status
   * and the whole append-only event log written against it. Reschedule chains still point at rows
   * that exist.
   *
   * No event is written for a content change, deliberately. The log records status transitions
   * and has neither an update nor a delete by design; a corrected description is not a
   * transition, and writing a self-transition to stand in for one would put something in the
   * audit trail that never happened. The plan row's `contentHash` is the record that the document
   * changed.
   */
  private suspend fun updateInPlace(
    existing: TrainingPlanEntity,
    validated: ValidatedPlan,
    hash: String,
    now: Long,
  ): ImportResult {
    planDao.update(
      existing.copy(
        name = validated.name,
        schemaVersion = validated.schemaVersion,
        timeZone = validated.timeZone,
        startDate = validated.startDate,
        description = validated.description,
        contentHash = hash,
      )
    )

    val stored = sessionDao.getByPlan(existing.id).associateBy { it.id }
    for (session in validated.sessions) {
      val fresh = session.toEntity(existing.id, now)
      val old = stored[session.id]
      if (old == null) {
        sessionDao.insert(fresh)
        eventDao.insert(
          SessionEventEntity(
            id = idGenerator(),
            sessionId = fresh.id,
            timestampUtc = now,
            fromStatus = null,
            toStatus = SessionStatus.PLANNED,
            source = EventSource.IMPORT,
            note = "Lisätty suunnitelmaan \"${validated.name}\"",
          )
        )
      } else {
        val merged = merge(old, fresh)
        if (contentDiffers(old, merged)) sessionDao.update(merged.copy(updatedAt = now))
      }
    }

    return ImportResult.Success(validated.id, validated.name, validated.sessions.size)
  }

  /**
   * Moves the whole plan so its first day is today, keeping every gap between sessions intact.
   *
   * A plan written weeks ago is still a good plan; its dates are just the coach's calendar rather
   * than yours. Shifting by a single delta preserves the structure — which day of the week each
   * session falls on relative to the start, and the rest days between them.
   *
   * `remindAtUtc` is recomputed from the new date rather than shifted by the same number of
   * milliseconds: a plan that crosses a daylight-saving boundary would otherwise drift by an hour
   * for every session on the far side of it.
   */
  private suspend fun shiftToStartToday(plan: ValidatedPlan): ValidatedPlan {
    val originalStart = LocalDate.parse(plan.startDate)
    val zone = runCatching { ZoneId.of(plan.timeZone) }.getOrDefault(clock.zone)
    val today = LocalDate.now(clock.withZone(zone))
    val delta = today.toEpochDay() - originalStart.toEpochDay()
    if (delta == 0L) return plan

    return plan.copy(
      startDate = today.toString(),
      sessions =
        plan.sessions.map { session ->
          val newDate = LocalDate.parse(session.scheduledDate).plusDays(delta)
          val time =
            session.scheduledTime?.let { LocalTime.parse(it, timeFormat) } ?: LocalTime.NOON
          session.copy(
            scheduledDate = newDate.toString(),
            remindAtUtc = ZonedDateTime.of(newDate, time, zone).toInstant().toEpochMilli(),
          )
        },
    )
  }

  /**
   * Removes plans left behind by earlier imports, and returns how many went.
   *
   * Imports before this behaviour existed only deactivated the plan they replaced, so a phone can
   * be carrying several dead programmes whose sessions still hold alarms. This is safe to run at
   * startup even though nothing else is: it can only delete rows that are already invisible —
   * every screen reads the active plan — so it cannot change what is in the calendar, which is
   * the thing an app update must never do.
   */
  suspend fun deleteReplacedPlans(): Int =
    db.withTransaction {
      // Guarded on there being an active plan, so a database holding only inactive rows — which
      // should not happen, but would mean every plan is deleted — is left alone to be looked at.
      if (planDao.getActivePlanId() == null) 0 else planDao.deleteInactive()
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
