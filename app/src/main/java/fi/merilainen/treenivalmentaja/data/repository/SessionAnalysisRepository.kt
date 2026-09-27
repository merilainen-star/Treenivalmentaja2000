package fi.merilainen.treenivalmentaja.data.repository

import fi.merilainen.treenivalmentaja.data.analysis.AnalysisClient
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisException
import fi.merilainen.treenivalmentaja.data.local.AnalysisDao
import fi.merilainen.treenivalmentaja.data.local.AnalysisRecord
import fi.merilainen.treenivalmentaja.data.local.StoredAnalysisState
import fi.merilainen.treenivalmentaja.domain.*
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by foreground and worker: repositories supply inputs; no UI flow is used as data. */
class SessionAnalysisRepository(
  private val dao: AnalysisDao,
  private val training: TrainingRepository,
  private val oura: OuraRepository,
  private val intervals: IntervalsRepository?,
  private val clients: Map<AnalysisProvider, AnalysisClient>,
  private val builder: AnalysisPromptBuilder = AnalysisPromptBuilder(),
  private val clock: Clock = Clock.systemDefaultZone(),
) {
  private val mutex = Mutex()
  private val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val active = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
  private val preparation = kotlinx.coroutines.flow.MutableStateFlow<Map<String, AiAnalysisState>>(emptyMap())
  val states = kotlinx.coroutines.flow.combine(dao.observe(), active) { rows, running ->
    rows.groupBy { it.sessionId }.mapValues { (id, records) ->
      val row = records.firstOrNull { it.kind == AiAnalysisKind.COMPLETED.name } ?: records.maxBy { it.createdAtUtc }
      when (row.state) {
        StoredAnalysisState.LOADED -> AiAnalysisState.Loaded(row.text, row.prompt)
        StoredAnalysisState.IN_FLIGHT -> if (id in running) AiAnalysisState.Loading else
          AiAnalysisState.Failed("Analyysi keskeytyi. Tarkista ennen uutta maksullista pyyntöä.", true)
        else -> AiAnalysisState.Failed(row.text, true)
      }
    }
  }.combine(preparation) { saved, pending -> saved + pending }

  suspend fun hasAttempt(id: String) = dao.get(id, AiAnalysisKind.COMPLETED.name) != null

  suspend fun analyse(id: String, kind: AiAnalysisKind, model: AnalysisModel, automatic: Boolean = false): Boolean {
    if (!requested.add(id)) return false
    return try { analyseOnce(id, kind, model, automatic) } finally { requested.remove(id) }
  }

  private suspend fun analyseOnce(id: String, kind: AiAnalysisKind, model: AnalysisModel, automatic: Boolean): Boolean = mutex.withLock {
    if (automatic && dao.get(id, kind.name) != null) return@withLock false
    val session = training.getSession(id) ?: return@withLock false
    if (kind == AiAnalysisKind.COMPLETED && session.type == WorkoutType.RUNNING && intervals != null) {
      preparation.update { it + (id to AiAnalysisState.Loading) }
      val ready = try {
        intervals.fetchLapsForSession(id)
      } catch (e: CancellationException) {
        preparation.update { it - id }
        throw e
      }
      if (!ready) {
        preparation.update { it + (id to AiAnalysisState.Failed(
          "Kellon kierrostietojen haku epäonnistui. Yritä uudelleen. AI-pyyntöä ei lähetetty.", true
        )) }
        return@withLock false // No provider call or persistent receipt: automatic retry is safe.
      }
      preparation.update { it - id }
    }
    val date = LocalDate.parse(session.scheduledDate)
    val recovery = oura.observeRecoveryRange(date.minusDays(AnalysisPromptBuilder.TREND_DAYS_BACK), date).first()
    val prompt = when (kind) {
      AiAnalysisKind.COMPLETED -> builder.completed(CompletedAnalysisInput(
        type = session.type, date = date, plannedDurationMin = session.durationMin,
        plannedIntensity = session.intensity, description = session.description,
        plannedRounds = session.rounds, exercises = session.exercises.orEmpty(),
        runSteps = session.runSteps.orEmpty(), guided = training.guidedProgressFor(id),
        timing = training.activeWorkoutOutcomeFor(id), oura = oura.observeMatchedMetrics().first()[id],
        run = intervals?.observeMatchedRunMetrics()?.first()?.get(id), recoveryByDay = recovery,
      ))
      AiAnalysisKind.UPCOMING -> builder.upcoming(UpcomingAnalysisInput(
        type = session.type, date = date, plannedDurationMin = session.durationMin,
        plannedIntensity = session.intensity, description = session.description,
        recoveryByDay = recovery, load = intervals?.loadOn(date),
      ))
    }
    val client = clients[model.provider] ?: return@withLock false
    val receipt = AnalysisRecord(id, kind.name, StoredAnalysisState.IN_FLIGHT, "", prompt, model.name, clock.millis())
    active.value = active.value + id
    try {
      dao.save(receipt)
      val text = client.analyse(prompt, model)
      if (training.getSession(id)?.planId != session.planId) return@withLock false
      dao.save(receipt.copy(state = StoredAnalysisState.LOADED, text = text))
      true
    } catch (e: CancellationException) {
      throw e // Keep receipt: the provider may have billed an interrupted request.
    } catch (e: AnalysisException) {
      if (training.getSession(id)?.planId == session.planId)
        dao.save(receipt.copy(state = StoredAnalysisState.FAILED, text = e.message ?: "AI-analyysi epäonnistui."))
      false
    } finally {
      active.value = active.value - id
    }
  }
}
