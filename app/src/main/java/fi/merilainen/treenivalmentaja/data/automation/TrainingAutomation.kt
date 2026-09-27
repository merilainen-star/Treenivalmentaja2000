package fi.merilainen.treenivalmentaja.data.automation

import fi.merilainen.treenivalmentaja.data.repository.*
import fi.merilainen.treenivalmentaja.data.settings.*
import fi.merilainen.treenivalmentaja.domain.*
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One coordinator shared by foreground changes and persistent WorkManager jobs. */
class TrainingAutomation(
  private val training: TrainingRepository,
  private val intervals: IntervalsRepository,
  private val analyses: SessionAnalysisRepository,
  private val preferences: AutomationSettingsStore,
  private val modelSettings: AnalysisSettingsStore,
  private val configured: () -> Set<AnalysisProvider>,
  private val connected: () -> Boolean,
  private val notifyAnalysis: (String) -> Unit,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val refreshRecovery: suspend () -> Unit = {},
) {
  private val mutex = Mutex()

  suspend fun run(): Boolean = mutex.withLock {
    if (!connected()) return@withLock true
    val settings = preferences.settings.first()
    if (!settings.completeRuns && !settings.analyseRuns && !settings.exportRuns) return@withLock true
    val zone = training.activePlanTimeZone()
    val today = LocalDate.now(clock.withZone(zone))
    var success = true
    if (settings.completeRuns || settings.analyseRuns) {
      val fromDate = today.minusDays(2)
      val result = intervals.sync(fromDate, today, zone)
      if (result is IntervalsSyncResult.Failure) return@withLock !result.canRetry
      val sessions = training.getSessions()
      val from = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
      val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
      intervals.matchActivities(sessions.map {
        PlannedSession(it.id, LocalDate.parse(it.scheduledDate)
          .atTime(it.scheduledTime?.let(LocalTime::parse) ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli(), it.type)
      }, from, to)
      val runs = intervals.recordedRuns(from, to)
      val candidates = AutomaticRunCompletion.candidates(sessions, runs, zone, clock.millis())
      for ((session, run) in candidates) {
        if (preferences.settings.first().completeRuns) training.autoCompleteRun(session, run.activityId)
      }
      if (settings.analyseRuns) {
        val completed = training.getSessions().filter { it.type == WorkoutType.RUNNING &&
          (it.status == SessionStatus.COMPLETED || candidates.any { candidate -> candidate.first.id == it.id }) }
        var recoveryRefreshed = false
        for (session in completed) {
          val run = runs.singleOrNull { it.matchedSessionId == session.id } ?: continue
          if (run.startUtc + run.durationSec * 1000 > clock.millis() - 120_000) continue
          if (analyses.hasAttempt(session.id)) continue
          // A stream request may have been deferred by the per-sync budget. Wait for detail.
          if ((run.distanceMeters ?: 0.0) >= 1000 && !intervals.hasFetchedRunDetail(run.activityId)) continue
          // FIT laps carry the repetitions. A pending/failed file request is not an empty file.
          if (!intervals.hasFetchedRunLaps(run.activityId)) continue
          if (!connected() || !preferences.settings.first().analyseRuns) break
          val model = modelSettings.modelFlow.first()
          if (model.provider !in configured()) break
          if (!recoveryRefreshed) { refreshRecovery(); recoveryRefreshed = true }
          if (!connected() || !preferences.settings.first().analyseRuns) break
          if (analyses.analyse(session.id, AiAnalysisKind.COMPLETED, model, automatic = true)) notifyAnalysis(session.id)
        }
      }
    }
    if (preferences.settings.first().exportRuns && connected()) {
      val sessions = training.getSessions()
      val signature = MessageDigest.getInstance("SHA-256").digest(
        (today.toString() + sessions.filter { it.isWatchRun() && LocalDate.parse(it.scheduledDate) in today..today.plusDays(13) }.toString()).toByteArray()
      ).joinToString("") { "%02x".format(it) }
      if (preferences.exportSignature.first() != signature) {
        when (val result = intervals.exportRuns(sessions, today, days = 14)) {
          is RunExportResult.Success -> {
            preferences.reportExport("${today}: ${result.uploaded} juoksua viety Intervals.icu:hun. Kelloon saapumista ei ole vahvistettu.")
            preferences.markExported(signature)
          }
          is RunExportResult.Failure -> {
            preferences.reportExport("Vienti vaatii huomiota: ${result.message}")
            success = false
          }
        }
      }
    }
    success
  }
}
