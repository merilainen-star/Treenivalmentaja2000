package fi.merilainen.treenivalmentaja.domain

/** Ordered watch guidance. Repeated intervals are represented by repeated work/rest steps. */
data class RunStep(
  val name: String = "",
  val durationSec: Int? = null,
  val distanceMeters: Int? = null,
  val paceSecPerKm: Int? = null,
) {
  fun isValid(): Boolean =
    name.isNotBlank() && name.length <= 80 && name.none { it == '\n' || it == '\r' } &&
      ((durationSec != null) xor (distanceMeters != null)) &&
      (durationSec == null || durationSec in 1..86400) &&
      (distanceMeters == null || distanceMeters in 1..200000) &&
      (paceSecPerKm == null || paceSecPerKm in 60..1800)

  fun summary(): String = buildString {
    append(name)
    append(" · ")
    append(durationSec?.let { "$it s" } ?: "$distanceMeters m")
    paceSecPerKm?.let { append(" · ${it / 60}:${(it % 60).toString().padStart(2, '0')} /km") }
  }
}

fun List<RunStep>.validRunSteps(): Boolean = size in 1..100 && all { it.isValid() }

fun TrainingSession.isWatchRun(): Boolean = type == WorkoutType.RUNNING && status in setOf(
  SessionStatus.PLANNED, SessionStatus.NOTIFIED, SessionStatus.REPLACED_WITH_LIGHTER_VERSION,
)
