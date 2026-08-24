package fi.merilainen.treenivalmentaja.data

import fi.merilainen.treenivalmentaja.data.importer.PlanJson
import fi.merilainen.treenivalmentaja.data.local.entity.SessionEventEntity
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity
import fi.merilainen.treenivalmentaja.domain.Intensity
import fi.merilainen.treenivalmentaja.domain.SessionEvent
import fi.merilainen.treenivalmentaja.domain.TrainingSession

/** Room entities never leave the data layer; these are the only conversions. */
internal fun WorkoutSessionEntity.toDomain(): TrainingSession =
  TrainingSession(
    id = id,
    planId = planId,
    type = type,
    weekNumber = weekNumber,
    scheduledDate = scheduledDate,
    scheduledTime = scheduledTime,
    remindAtUtc = remindAtUtc,
    timeIsFixed = timeIsFixed,
    reminderOverride = reminderOverride,
    durationMin = durationMin,
    distanceKm = distanceKm,
    intensity = intensity?.let { raw -> Intensity.entries.firstOrNull { it.name == raw } },
    rounds = rounds,
    roundsMin = roundsMin,
    roundsMax = roundsMax,
    targetPace = targetPace,
    warmupSec = warmupSec,
    roundRestSec = roundRestSec,
    exercises = PlanJson.decodeExercises(exercisesJson),
    lighterAlternative = PlanJson.decodeLighter(lighterAlternativeJson),
    description = description,
    status = status,
    appliedLighterVariant = appliedLighterVariant,
    originalSessionId = originalSessionId,
  )

internal fun TrainingSession.toEntity(planId: String, updatedAt: Long): WorkoutSessionEntity =
  WorkoutSessionEntity(
    id = id,
    planId = planId,
    type = type,
    weekNumber = weekNumber,
    scheduledDate = scheduledDate,
    scheduledTime = scheduledTime,
    remindAtUtc = remindAtUtc,
    timeIsFixed = timeIsFixed,
    reminderOverride = reminderOverride,
    durationMin = durationMin,
    distanceKm = distanceKm,
    intensity = intensity?.name,
    rounds = rounds,
    roundsMin = roundsMin,
    roundsMax = roundsMax,
    targetPace = targetPace,
    warmupSec = warmupSec,
    roundRestSec = roundRestSec,
    exercisesJson = PlanJson.encodeExercises(exercises),
    lighterAlternativeJson = PlanJson.encodeLighter(lighterAlternative),
    description = description,
    status = status,
    appliedLighterVariant = appliedLighterVariant,
    originalSessionId = originalSessionId,
    updatedAt = updatedAt,
  )

internal fun SessionEventEntity.toDomain(): SessionEvent =
  SessionEvent(
    id = id,
    sessionId = sessionId,
    timestampUtc = timestampUtc,
    fromStatus = fromStatus,
    toStatus = toStatus,
    source = source,
    note = note,
    payloadJson = payloadJson,
  )
