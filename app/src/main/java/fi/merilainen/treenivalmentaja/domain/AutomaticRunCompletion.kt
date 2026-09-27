package fi.merilainen.treenivalmentaja.domain

import java.time.Instant
import java.time.ZoneId

data class RecordedRun(
  val activityId: String, val startUtc: Long, val durationSec: Long,
  val distanceMeters: Double?, val source: String?, val matchedSessionId: String?,
)

/** Conservative automation: one run and one planned running session on that local day. */
object AutomaticRunCompletion {
  fun candidates(sessions: List<TrainingSession>, runs: List<RecordedRun>, zone: ZoneId, nowUtc: Long): List<Pair<TrainingSession, RecordedRun>> =
    runs.mapNotNull { run ->
      val day = Instant.ofEpochMilli(run.startUtc).atZone(zone).toLocalDate().toString()
      val sameDay = sessions.filter { it.type == WorkoutType.RUNNING && it.scheduledDate == day &&
        it.status !in setOf(SessionStatus.RESCHEDULED, SessionStatus.CANCELLED) }
      val session = sameDay.singleOrNull() ?: return@mapNotNull null
      if (runs.count { Instant.ofEpochMilli(it.startUtc).atZone(zone).toLocalDate().toString() == day } != 1) return@mapNotNull null
      if (session.status !in setOf(SessionStatus.PLANNED, SessionStatus.NOTIFIED, SessionStatus.STARTED,
          SessionStatus.REPLACED_WITH_LIGHTER_VERSION) || session.id != run.matchedSessionId || run.source != "SUUNTO") return@mapNotNull null
      if (run.startUtc + run.durationSec * 1000 > nowUtc - 120_000) return@mapNotNull null
      val plannedSeconds = session.durationMin?.takeIf { it > 0 }?.times(60L)
      val plannedMeters = session.distanceKm?.takeIf { it > 0 }?.times(1000)
      // Distance-only easy runs are valid plans too. Missing targets are not zero targets.
      if (plannedSeconds == null && plannedMeters == null) return@mapNotNull null
      if (run.durationSec < maxOf(300L, (plannedSeconds ?: 0L) / 2)) return@mapNotNull null
      if ((run.distanceMeters ?: return@mapNotNull null) < maxOf(500.0, (plannedMeters ?: 0.0) / 2)) return@mapNotNull null
      session to run
    }
}
