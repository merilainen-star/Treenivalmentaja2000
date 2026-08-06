package fi.merilainen.treenivalmentaja.data.importer

import com.squareup.moshi.JsonClass

/**
 * Wire model for the Treenivalmentaja Training Plan Schema v1 (`docs/PLAN_SCHEMA.md`).
 *
 * Every field is nullable on purpose. Moshi's job here is only to turn text into a tree; deciding
 * what is *missing* or *wrong* is [PlanValidator]'s job, because it can report every problem at
 * once with a JSON path and a Finnish message. Letting Moshi throw on a missing field would give
 * the user one cryptic error at a time.
 */
@JsonClass(generateAdapter = true)
data class PlanDocumentDto(
  val schemaVersion: Int? = null,
  val plan: PlanMetaDto? = null,
  val weeks: List<WeekDto?>? = null,
)

@JsonClass(generateAdapter = true)
data class PlanMetaDto(
  val id: String? = null,
  val name: String? = null,
  val timeZone: String? = null,
  val startDate: String? = null,
  val description: String? = null,
  val author: String? = null,
)

@JsonClass(generateAdapter = true)
data class WeekDto(
  val weekNumber: Int? = null,
  val focus: String? = null,
  val sessions: List<SessionDto?>? = null,
)

@JsonClass(generateAdapter = true)
data class SessionDto(
  val id: String? = null,
  val type: String? = null,
  val date: String? = null,
  val time: String? = null,
  val timeIsFixed: Boolean? = null,
  val durationMin: Int? = null,
  val distanceKm: Double? = null,
  val intensity: String? = null,
  val rounds: Int? = null,
  val roundsMin: Int? = null,
  val roundsMax: Int? = null,
  val targetPace: String? = null,
  val warmupSec: Int? = null,
  val description: String? = null,
  val exercises: List<ExerciseDto?>? = null,
  val lighterAlternative: LighterAlternativeDto? = null,
)

@JsonClass(generateAdapter = true)
data class ExerciseDto(
  val name: String? = null,
  val sets: Int? = null,
  val reps: Int? = null,
  val repsMin: Int? = null,
  val repsMax: Int? = null,
  val perSide: Boolean? = null,
  val weightKg: Double? = null,
  val durationSec: Int? = null,
  val restSec: Int? = null,
  val notes: String? = null,
)

@JsonClass(generateAdapter = true)
data class LighterAlternativeDto(
  val durationMin: Int? = null,
  val distanceKm: Double? = null,
  val intensity: String? = null,
  val rounds: Int? = null,
  val roundsMin: Int? = null,
  val roundsMax: Int? = null,
  val targetPace: String? = null,
  val warmupSec: Int? = null,
  val description: String? = null,
  val exercises: List<ExerciseDto?>? = null,
)
