package fi.merilainen.treenivalmentaja.domain

/** Kind of training session. Mirrors the `type` enum of the plan JSON schema v1. */
enum class WorkoutType(val title: String) {
  RUNNING("Juoksu"),
  STRENGTH("Lihaskunto"),
  SKIING("Hiihto"),
}

/** Planned effort level. Optional in the plan JSON schema v1. */
enum class Intensity(val title: String) {
  EASY("Kevyt"),
  MODERATE("Reipas"),
  HARD("Kova"),
  MAX("Maksimi"),
}

/** Who caused a [SessionEvent]. */
enum class EventSource {
  /** The person tapped something. */
  USER,
  /** The local deterministic rule engine. */
  ENGINE,
  /** An AlarmManager reminder fired. */
  ALARM,
  /** A background Oura sync matched or updated the session. */
  OURA_SYNC,
  /** Plan import or seeding created the session. */
  IMPORT,
  /** A user-approved AI advisor proposal. */
  AI_ADVISOR,
}

/** A single movement inside a strength session. Serialised into `exercisesJson`. */
data class Exercise(
  val name: String,
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

/**
 * The explicit lighter variant a plan may offer for a session. Applied when the user picks
 * "Kevyempi versio"; see `docs/TRAINING_ENGINE.md`.
 */
data class LighterAlternative(
  val durationMin: Int? = null,
  val distanceKm: Double? = null,
  val intensity: Intensity? = null,
  val rounds: Int? = null,
  val roundsMin: Int? = null,
  val roundsMax: Int? = null,
  val targetPace: String? = null,
  val warmupSec: Int? = null,
  val description: String? = null,
  val exercises: List<Exercise>? = null,
)

/**
 * A training session as the rest of the app sees it. Room entities never leave the data layer.
 */
data class TrainingSession(
  val id: String,
  val planId: String,
  val type: WorkoutType,
  val weekNumber: Int,
  /** Local date, `YYYY-MM-DD`. */
  val scheduledDate: String,
  /** Local time, `HH:mm`. */
  val scheduledTime: String,
  /** Absolute instant of the session start, epoch millis UTC. What AlarmManager uses. */
  val scheduledAtUtc: Long,
  val durationMin: Int? = null,
  val distanceKm: Double? = null,
  val intensity: Intensity? = null,
  val rounds: Int? = null,
  val roundsMin: Int? = null,
  val roundsMax: Int? = null,
  val targetPace: String? = null,
  val warmupSec: Int? = null,
  val exercises: List<Exercise>? = null,
  val lighterAlternative: LighterAlternative? = null,
  val description: String? = null,
  val status: SessionStatus = SessionStatus.PLANNED,
  val appliedLighterVariant: Boolean = false,
  /** Set when this session was created by rescheduling another one. */
  val originalSessionId: String? = null,
)

/** One immutable entry of a session's history. */
data class SessionEvent(
  val id: String,
  val sessionId: String,
  val timestampUtc: Long,
  /** `null` only for the creation event. */
  val fromStatus: SessionStatus?,
  val toStatus: SessionStatus,
  val source: EventSource,
  val note: String? = null,
  val payloadJson: String? = null,
)
