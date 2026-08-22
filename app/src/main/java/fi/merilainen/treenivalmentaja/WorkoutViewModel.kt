package fi.merilainen.treenivalmentaja

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.merilainen.treenivalmentaja.data.importer.ImportResult
import fi.merilainen.treenivalmentaja.data.importer.PendingImport
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.security.CredentialSaveResult
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState
import fi.merilainen.treenivalmentaja.data.oura.OuraConnection
import fi.merilainen.treenivalmentaja.data.oura.OuraConnectionState
import fi.merilainen.treenivalmentaja.data.repository.OuraRepository
import fi.merilainen.treenivalmentaja.data.repository.OuraSyncResult
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsConnection
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsConnectionState
import fi.merilainen.treenivalmentaja.data.intervals.IntervalsException
import fi.merilainen.treenivalmentaja.data.repository.IntervalsBackfillResult
import fi.merilainen.treenivalmentaja.data.repository.IntervalsRepository
import fi.merilainen.treenivalmentaja.data.repository.IntervalsSyncResult
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisClient
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisConnection
import fi.merilainen.treenivalmentaja.data.analysis.AnalysisException
import fi.merilainen.treenivalmentaja.data.settings.AnalysisSettingsStore
import fi.merilainen.treenivalmentaja.domain.AiAnalysisAvailability
import fi.merilainen.treenivalmentaja.domain.AiAnalysisKind
import fi.merilainen.treenivalmentaja.domain.AiAnalysisState
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisPromptBuilder
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import fi.merilainen.treenivalmentaja.domain.CompletedAnalysisInput
import fi.merilainen.treenivalmentaja.domain.GuidedProgress
import fi.merilainen.treenivalmentaja.domain.Intensity
import fi.merilainen.treenivalmentaja.domain.UpcomingAnalysisInput
import fi.merilainen.treenivalmentaja.domain.CompletedRunMetrics
import fi.merilainen.treenivalmentaja.domain.IntervalsActivityRef
import fi.merilainen.treenivalmentaja.domain.IntervalsRawResponse
import fi.merilainen.treenivalmentaja.domain.CompletedSessionMetrics
import fi.merilainen.treenivalmentaja.domain.DailyRecovery
import fi.merilainen.treenivalmentaja.domain.OuraDiagnostics
import fi.merilainen.treenivalmentaja.domain.PlannedSession
import fi.merilainen.treenivalmentaja.domain.ReadinessAdvice
import fi.merilainen.treenivalmentaja.domain.MissedProposalDismissalStore
import fi.merilainen.treenivalmentaja.domain.MissedSessionsProposal
import fi.merilainen.treenivalmentaja.domain.ReadinessAdviceUseCase
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
import fi.merilainen.treenivalmentaja.domain.UpdateStatus
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a screen needs to draw one session card. */
data class Workout(
  val id: String,
  val dayOffset: Int,
  val type: WorkoutType,
  val time: String,
  val durationMin: Int,
  val description: String,
  val status: SessionStatus = SessionStatus.PLANNED,
  val appliedLighterVariant: Boolean = false,
  /** True when this session exists because another one was moved onto this day. */
  val movedHere: Boolean = false,
  /**
   * The session's movements as the plan defined them.
   *
   * Empty for a plan that supplies none, in which case the screens fall back to reading them out
   * of [description] with `parseStrengthDescription`. That fallback is a guess — it decides what
   * is a movement by counting commas — so a plan that carries real exercises should be shown from
   * these instead.
   */
  val exercises: List<Exercise> = emptyList(),
  /** Circuit rounds the whole exercise list is repeated for, when the plan says so. */
  val rounds: Int = 1,
  /**
   * The effort the plan asked for, when it said.
   *
   * Plan Schema v1's only notion of intended load — there is no numeric target — which is why the
   * AI analysis of an *upcoming* session leans on it: without it the model would be told a duration
   * and a description and left to guess how hard the session is meant to be.
   */
  val intensity: Intensity? = null,
)

// There was a RecoveryState here, with three readings and their advice. Nothing ever produced
// anything but the middle one, so the Today screen showed the same verdict every day. It comes
// back when Oura can decide between them; until then the app says nothing about recovery rather
// than saying the same wrong-shaped thing daily. See docs/ROADMAP.md.


/** Result of the last plan import, shown once and then dismissed. */
data class ImportFeedback(val title: String, val detail: String, val isError: Boolean)

/**
 * An import held at the door, with the document it would write and what that would cost.
 *
 * The raw JSON travels with it because the confirmation happens after the file has been read: the
 * picker grants one read, and re-opening the same file to answer a dialog is not something the
 * user should have to do.
 */
data class PendingImportPrompt(
  val rawJson: String,
  val startToday: Boolean,
  /** The name the incoming document gives itself. */
  val planName: String,
  val action: PendingImport,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkoutViewModel(
  private val repository: TrainingRepository,
  private val engine: TrainingEngine,
  private val settingsStore: NotificationSettingsStore,
  private val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase,
  private val checkForUpdateUseCase: CheckForUpdateUseCase,
  private val loadExerciseGuideUseCase: LoadExerciseGuideUseCase,
  private val ouraConnection: OuraConnection,
  private val ouraRepository: OuraRepository,
  private val intervalsConnection: IntervalsConnection? = null,
  private val intervalsRepository: IntervalsRepository? = null,
  private val readinessAdviceUseCase: ReadinessAdviceUseCase = ReadinessAdviceUseCase(),
  /**
   * The AI analysis collaborators, all nullable for the reason [intervalsConnection] is: the
   * existing unit tests have no Keystore to build a real key store against, and a ViewModel that
   * could not be constructed without one would make every test an instrumented test. A missing
   * connection reads as "no key", which is also true.
   */
  private val analysisConnection: AnalysisConnection? = null,
  private val analysisClients: Map<AnalysisProvider, AnalysisClient> = emptyMap(),
  private val analysisSettingsStore: AnalysisSettingsStore? = null,
  private val analysisPromptBuilder: AnalysisPromptBuilder = AnalysisPromptBuilder(),
  /**
   * Where a refusal of the missed-session card is written down, nullable for the same reason the
   * collaborators above are: it is backed by DataStore, whose IO sits outside a test's scheduler,
   * and the existing ViewModel tests cover the refusal itself without needing it to survive a
   * process they never restart. Null means the refusal lives only in memory, which is what this
   * class did everywhere before the store existed.
   */
  private val missedProposalDismissalStore: MissedProposalDismissalStore? = null,
  /**
   * **`systemDefaultZone`, not `systemUTC`** — and the difference is a bug, not a preference.
   *
   * Nothing in production passes a clock, so all three classes that must agree about "today" run on
   * their defaults: this one, [TrainingEngine] and [TrainingRepository]. The other two default to
   * the device zone. A UTC default here made this class disagree with both of them for the window
   * between construction and the first emission of `observeActivePlanTimeZone` — which in Finland
   * means the app briefly calls it yesterday between midnight and 03:00, and fires the first Room
   * query for the wrong day. The plan's zone still wins once it arrives; this is only about what is
   * true before it does.
   */
  private val clock: Clock = Clock.systemDefaultZone(),
  /**
   * Where the midnight rollover waits. Injectable **so that it can be tested at all**.
   *
   * It defaults to [Dispatchers.Default] rather than the ViewModel's main dispatcher on purpose: the
   * loop below re-arms itself forever, so on a test dispatcher every existing test's
   * `advanceUntilIdle()` would drive a fixed clock through the same midnight without end. Keeping
   * the default off the test scheduler leaves those tests untouched, while a test that actually
   * wants to watch a day turn can pass its own dispatcher and drive virtual time deliberately.
   */
  private val rolloverDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

  private val _planZone = MutableStateFlow(clock.zone)
  private val _currentDate = MutableStateFlow(LocalDate.now(clock))
  val currentDate: StateFlow<LocalDate> = _currentDate.asStateFlow()
  private var dateRolloverJob: Job? = null

  /**
   * Re-evaluates "today" without recreating the ViewModel.
   *
   * Screens call this on resume, and [scheduleDateRollover] also calls it at midnight while the app
   * remains in the foreground. The active plan's zone is the authority: plan dates, reminders,
   * matching windows and missed-session decisions must all agree even when the phone is travelling.
   */
  fun refreshCurrentDate() {
    _currentDate.value = LocalDate.now(clock.withZone(_planZone.value))
    scheduleDateRollover()
  }

  private fun scheduleDateRollover() {
    dateRolloverJob?.cancel()
    dateRolloverJob =
      viewModelScope.launch(rolloverDispatcher) {
        while (isActive) {
          val zone = _planZone.value
          val now = clock.instant()
          val nextMidnight =
            LocalDate.now(clock.withZone(zone)).plusDays(1).atStartOfDay(zone).toInstant()
          delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
          _currentDate.value = LocalDate.now(clock.withZone(zone))
        }
      }
  }

  val workouts: StateFlow<List<Workout>> =
    combine(repository.observeSessions(), currentDate) { sessions, today ->
        sessions.toWorkouts(today)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val notificationSettings: StateFlow<NotificationSettings> =
    settingsStore.settingsFlow.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5_000),
      NotificationSettings()
    )

  private val _importFeedback = MutableStateFlow<ImportFeedback?>(null)
  val importFeedback: StateFlow<ImportFeedback?> = _importFeedback.asStateFlow()

  /** Non-null while an import is waiting to be told whether it may touch the stored plan. */
  private val _pendingImport = MutableStateFlow<PendingImportPrompt?>(null)
  val pendingImport: StateFlow<PendingImportPrompt?> = _pendingImport.asStateFlow()

  private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
  val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

  /** Whether Oura is connected, being connected, or cannot be. */
  val ouraState: StateFlow<OuraConnectionState> = ouraConnection.state

  /**
   * Today's Oura reading, straight from Room.
   *
   * `null` means Oura has never been asked about today — which the card says differently from a day
   * Oura answered about with no numbers in it. The screen observes the database, never the network:
   * a failed sync leaves this showing yesterday's truth rather than an error.
   */
  val todayRecovery: StateFlow<DailyRecovery?> =
    currentDate
      .flatMapLatest(ouraRepository::observeDay)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /**
   * What Oura recorded for each session that was actually done, keyed by session id.
   *
   * The plan says what was asked for; this is what happened. A session with no entry here simply
   * has nothing from Oura — which is the ordinary state of a session done without the ring, or one
   * Oura has not processed yet.
   */
  val completedMetrics: StateFlow<Map<String, CompletedSessionMetrics>> =
    ouraRepository
      .observeMatchedMetrics()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  /**
   * Today's Oura workouts that no planned session claims.
   *
   * Shown on their own rather than dropped. A spontaneous walk belongs to no session, and a session
   * the matcher could not place would otherwise be invisible — which looks exactly like never having
   * fetched it.
   */
  /**
   * Unclaimed Oura workouts across the calendar's span, by the day they happened on.
   *
   * Needed once matching started requiring the activity to fit: a walk no longer pretends to be a
   * strength session, and without this it would simply disappear instead.
   */
  val unmatchedByDay: StateFlow<Map<LocalDate, List<CompletedSessionMetrics>>> =
    combine(currentDate, _planZone) { today, zone -> today to zone }
      .flatMapLatest { (today, zone) ->
        ouraRepository.observeUnmatchedByDay(
          from = today.minusDays(CALENDAR_DAYS_BACK),
          to = today.plusDays(1),
          zone = zone,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  val unmatchedToday: StateFlow<List<CompletedSessionMetrics>> =
    combine(currentDate, _planZone) { today, zone -> today to zone }
      .flatMapLatest { (today, zone) -> ouraRepository.observeUnmatchedOn(today, zone) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /**
   * Readiness across the same span the week list scrolls back over, by the day it belongs to.
   *
   * Only readiness is read here — sleep and activity stay a today-only reading on the Today card.
   * A day with no entry is a day Oura has never answered about, same as [todayRecovery]'s `null`.
   */
  val recoveryByDay: StateFlow<Map<LocalDate, DailyRecovery>> =
    currentDate
      .flatMapLatest { today ->
        ouraRepository.observeRecoveryRange(
          from = today.minusDays(CALENDAR_DAYS_BACK),
          to = today,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  /**
   * Set when a login is waiting for a browser to be opened, and cleared the moment one is.
   *
   * The ViewModel builds the URL — it is the half that knows the PKCE verifier and has to write it
   * down before the browser sees anything — but opening a browser is something only a real screen
   * can do, so it is handed over as state rather than performed here.
   */
  private val _ouraAuthorizationUrl = MutableStateFlow<String?>(null)
  val ouraAuthorizationUrl: StateFlow<String?> = _ouraAuthorizationUrl.asStateFlow()

  /** `null` means no guide sheet is open. */
  private val _guideState = MutableStateFlow<ExerciseGuideState?>(null)
  val guideState: StateFlow<ExerciseGuideState?> = _guideState.asStateFlow()

  /** The exercise the open sheet is about, so "Yritä uudelleen" knows what to retry. */
  private var guideExercise: Exercise? = null

  init {
    viewModelScope.launch {
      repository.observeActivePlanTimeZone().collect { zone ->
        _planZone.value = zone
        refreshCurrentDate()
      }
    }
    viewModelScope.launch {
      // Seeding only writes when the database is empty, so it cannot disturb an imported plan.
      repository.seedIfEmpty()
      // Neither can this: it removes plans an import has already replaced, which no screen reads.
      // It is here rather than only in the importer so that phones carrying plans from before
      // imports deleted them are cleaned up without having to import again.
      repository.deleteReplacedPlans()
    }
  }

  // ------------------------------------------------------------------ Oura

  /** Starts a login. The screen opens whatever lands in [ouraAuthorizationUrl]. */
  fun connectOura() {
    viewModelScope.launch { _ouraAuthorizationUrl.value = ouraConnection.beginAuthorization() }
  }

  /**
   * The browser is open, so the URL has been used.
   *
   * Cleared rather than kept, because it carries a `code_challenge` for one specific attempt —
   * reopening the same URL later would send a challenge whose verifier has already been spent.
   */
  fun ouraAuthorizationOpened() {
    _ouraAuthorizationUrl.value = null
  }

  /** Nothing was opened — no browser on the device, or the intent was refused. */
  fun ouraAuthorizationFailedToOpen() {
    _ouraAuthorizationUrl.value = null
    viewModelScope.launch { ouraConnection.cancelAuthorization() }
  }

  /**
   * Stores the client id and secret pasted from Oura's developer portal.
   *
   * Blank fields are refused by the connection rather than here; the card already disables the
   * button, and two places deciding the same thing is how they come to disagree.
   */
  fun saveOuraCredentials(clientId: String, clientSecret: String) {
    viewModelScope.launch { ouraConnection.saveCredentials(clientId, clientSecret) }
  }

  /** The way back to the credential fields, for a client id that was pasted wrong. */
  fun forgetOuraCredentials() {
    viewModelScope.launch { ouraConnection.forgetCredentials() }
  }

  /**
   * Fetches the last few days from Oura now.
   *
   * Called when the Today screen appears, and harmless when it is not connected — the client
   * refuses without a token and the failure is swallowed into the state below. Deliberately quiet:
   * this runs without anyone asking for it, so a network that is not there must not produce a
   * dialog.
   */
  fun syncOura() {
    if (ouraState.value != OuraConnectionState.Connected) return
    if (_ouraSyncing.value) return
    viewModelScope.launch {
      _ouraSyncing.value = true
      val today = currentDate.value
      _lastSyncFailure.value =
        when (val result = ouraRepository.sync(from = today.minusDays(SYNC_DAYS), to = today)) {
          is OuraSyncResult.Success -> null
          is OuraSyncResult.Failure -> result.message
        }
      matchCompletedWorkouts(today)
      _ouraSyncing.value = false
    }
  }

  /**
   * Ties what Oura recorded to what the plan asked for, over the same days the sync covered.
   *
   * Runs after every sync rather than once: a session moved to another day, or completed late,
   * changes which workout belongs to it, and the pairing is cheap to recompute from what is
   * already stored.
   */
  private suspend fun matchCompletedWorkouts(today: LocalDate) {
    val zone = _planZone.value
    val from = today.minusDays(SYNC_DAYS).atStartOfDay(zone).toInstant()
    val to = today.plusDays(1).atStartOfDay(zone).toInstant()
    val earliest = today.minusDays(SYNC_DAYS)
    val sessions =
      repository
        .getSessions()
        .filter {
          val date = runCatching { LocalDate.parse(it.scheduledDate) }.getOrNull()
          date != null && !date.isBefore(earliest) && !date.isAfter(today)
        }
        .map { PlannedSession(id = it.id, scheduledAtUtc = it.remindAtUtc, type = it.type) }
    ouraRepository.matchWorkouts(sessions, from.toEpochMilli(), to.toEpochMilli())
  }

  private val _ouraSyncing = MutableStateFlow(false)
  val ouraSyncing: StateFlow<Boolean> = _ouraSyncing.asStateFlow()

  /** The last sync's failure, or `null`. Shown on the card as a footnote, never as a dialog. */
  private val _lastSyncFailure = MutableStateFlow<String?>(null)
  val lastSyncFailure: StateFlow<String?> = _lastSyncFailure.asStateFlow()

  /**
   * Asks Oura what it has, and reports it without storing anything.
   *
   * Exists because of a dead end this app actually hit: a session visible in Oura's own app and
   * absent here, with no way from the outside to tell whether the API had not returned it, whether
   * parsing had dropped it, or whether it had been stored and not drawn. The phone makes the
   * requests, so the phone answers — which is also why nobody has to hand their Oura credentials to
   * anyone to debug this.
   */
  fun runOuraDiagnostics() {
    if (ouraState.value != OuraConnectionState.Connected) return
    viewModelScope.launch {
      _diagnostics.value = null
      _diagnosing.value = true
      val today = currentDate.value
      _diagnostics.value = ouraRepository.diagnose(from = today.minusDays(SYNC_DAYS), to = today)
      _diagnosing.value = false
    }
  }

  private val _diagnostics = MutableStateFlow<OuraDiagnostics?>(null)
  val ouraDiagnostics: StateFlow<OuraDiagnostics?> = _diagnostics.asStateFlow()

  private val _diagnosing = MutableStateFlow(false)
  val ouraDiagnosing: StateFlow<Boolean> = _diagnosing.asStateFlow()

  /** Drops the tokens and the cached Oura rows. The training plan is untouched. */
  fun disconnectOura() {
    viewModelScope.launch { ouraConnection.disconnect() }
  }

  fun dismissOuraFailure() {
    viewModelScope.launch { ouraConnection.dismissFailure() }
  }

  // ------------------------------------------------------------------ intervals.icu

  /**
   * Whether an intervals.icu key is stored, and what the last test of it said.
   *
   * The connection is nullable on the constructor so the existing unit tests, which have no
   * Keystore to build a real one against, keep constructing the ViewModel; a missing connection
   * reads as NotConfigured, which is also true.
   */
  val intervalsState: StateFlow<IntervalsConnectionState> =
    intervalsConnection?.state
      ?: MutableStateFlow<IntervalsConnectionState>(IntervalsConnectionState.NotConfigured)
        .asStateFlow()

  /** Stores the key pasted from intervals.icu's settings page, then checks that it works. */
  fun saveIntervalsApiKey(key: String) {
    val connection = intervalsConnection ?: return
    viewModelScope.launch {
      if (connection.saveApiKey(key) == CredentialSaveResult.Success) {
        // Tested immediately rather than at the next sync. A key pasted with a character missing
        // would otherwise look accepted and then quietly fetch nothing.
        connection.testKey()
        syncIntervals()
      }
    }
  }

  fun testIntervalsApiKey() {
    val connection = intervalsConnection ?: return
    viewModelScope.launch { connection.testKey() }
  }

  /** Forgets the key and the cached activities. The training plan is untouched. */
  fun clearIntervalsApiKey() {
    val connection = intervalsConnection ?: return
    viewModelScope.launch { connection.clearApiKey() }
  }

  fun dismissIntervalsFailure() {
    val connection = intervalsConnection ?: return
    viewModelScope.launch { connection.dismissFailure() }
  }

  /**
   * Fetches the last few days from intervals.icu now. Called beside [syncOura] when a screen
   * appears, and just as deliberately quiet — this runs without anyone asking for it, so a network
   * that is not there must not produce a dialog.
   *
   * The window overlaps every previous one on purpose: an activity can reach intervals.icu late,
   * and re-fetching is free of consequence because rows are keyed on the service's own activity id
   * and upserted.
   */
  fun syncIntervals() {
    val repository = intervalsRepository ?: return
    if (intervalsState.value == IntervalsConnectionState.NotConfigured) return
    if (_intervalsSyncing.value) return
    viewModelScope.launch {
      _intervalsSyncing.value = true
      val today = currentDate.value
      _intervalsSyncFailure.value =
        when (
          val result =
            repository.sync(
              from = today.minusDays(SYNC_DAYS),
              to = today,
              zone = _planZone.value,
            )
        ) {
          is IntervalsSyncResult.Success -> null
          is IntervalsSyncResult.Failure -> result.message
        }
      matchIntervalsActivities(today)
      _intervalsSyncing.value = false
    }
  }

  /** The same pairing run the Oura workouts get, over the same window. */
  private suspend fun matchIntervalsActivities(today: LocalDate) {
    val intervalsRepository = intervalsRepository ?: return
    val zone = _planZone.value
    val from = today.minusDays(SYNC_DAYS).atStartOfDay(zone).toInstant()
    val to = today.plusDays(1).atStartOfDay(zone).toInstant()
    val earliest = today.minusDays(SYNC_DAYS)
    val sessions =
      repository
        .getSessions()
        .filter {
          val date = runCatching { LocalDate.parse(it.scheduledDate) }.getOrNull()
          date != null && !date.isBefore(earliest) && !date.isAfter(today)
        }
        .map { PlannedSession(id = it.id, scheduledAtUtc = it.remindAtUtc, type = it.type) }
    intervalsRepository.matchActivities(sessions, from.toEpochMilli(), to.toEpochMilli())
  }

  private val _intervalsSyncing = MutableStateFlow(false)

  /** The last sync's failure, or `null`. Shown on the card as a footnote, never as a dialog. */
  private val _intervalsSyncFailure = MutableStateFlow<String?>(null)
  val intervalsSyncFailure: StateFlow<String?> = _intervalsSyncFailure.asStateFlow()

  // ------------------------------------------------------------------ backfill

  /**
   * Non-null while a backfill is running, counting the activities stored so far.
   *
   * A count rather than a percentage: the walk does not know how many years it will take until it
   * meets the end of the history, so a progress bar would be inventing a denominator.
   */
  private val _backfillProgress = MutableStateFlow<Int?>(null)
  val backfillProgress: StateFlow<Int?> = _backfillProgress.asStateFlow()

  /** What the last backfill managed, shown once and then dismissed. */
  private val _backfillResult = MutableStateFlow<IntervalsBackfillResult?>(null)
  val backfillResult: StateFlow<IntervalsBackfillResult?> = _backfillResult.asStateFlow()

  /**
   * Re-reads the whole history, so a column added today gets values for activities from before it.
   *
   * The ordinary sync looks back a fortnight; without this, an activity older than that keeps a
   * null in every column added after it was first stored.
   */
  fun backfillIntervals() {
    val repository = intervalsRepository ?: return
    if (intervalsState.value == IntervalsConnectionState.NotConfigured) return
    if (_backfillProgress.value != null) return
    viewModelScope.launch {
      _backfillProgress.value = 0
      _backfillResult.value = null
      val today = currentDate.value
      val result =
        repository.backfill(
          today = today,
          zone = _planZone.value,
          onYearDone = { stored -> _backfillProgress.value = stored },
        )
      // Older activities have no planned sessions to belong to, so matching stays on its usual
      // window rather than sweeping years of history for pairs that cannot exist.
      matchIntervalsActivities(today)
      _backfillProgress.value = null
      _backfillResult.value = result
    }
  }

  fun dismissBackfillResult() {
    _backfillResult.value = null
  }

  // ------------------------------------------------------------------ raw-data diagnostics

  /**
   * The last raw response fetched from intervals.icu, or `null` before anything has been.
   *
   * Held here and nowhere else: nothing about this reaches the database, because a diagnostics
   * call that quietly wrote rows would make the screen a source of the confusion it exists to
   * resolve.
   */
  private val _rawResponse = MutableStateFlow<IntervalsRawResponse?>(null)
  val rawResponse: StateFlow<IntervalsRawResponse?> = _rawResponse.asStateFlow()

  private val _rawLoading = MutableStateFlow(false)
  val rawLoading: StateFlow<Boolean> = _rawLoading.asStateFlow()

  /** A network failure, which has no HTTP status to show and so needs saying in words. */
  private val _rawError = MutableStateFlow<String?>(null)
  val rawError: StateFlow<String?> = _rawError.asStateFlow()

  /** The activities available to inspect one at a time, newest first. */
  private val _rawActivityRefs = MutableStateFlow<List<IntervalsActivityRef>>(emptyList())
  val rawActivityRefs: StateFlow<List<IntervalsActivityRef>> = _rawActivityRefs.asStateFlow()

  /**
   * Fetches the activities list unfiltered — every field, not the eighteen the sync asks for.
   *
   * A week rather than the sync's fortnight: this response carries all 183 fields per activity, so
   * a shorter range keeps it to something a person can actually read.
   */
  fun fetchRawActivities() {
    val repository = intervalsRepository ?: return
    if (_rawLoading.value) return
    viewModelScope.launch {
      _rawLoading.value = true
      _rawError.value = null
      val today = currentDate.value
      try {
        _rawResponse.value =
          repository.fetchRawActivities(from = today.minusDays(RAW_DAYS), to = today)
      } catch (e: IntervalsException) {
        _rawResponse.value = null
        _rawError.value = e.message
      }
      // Offered after the list has been fetched, so the picker is never empty for want of asking.
      val zone = _planZone.value
      _rawActivityRefs.value =
        repository.recentActivityRefs(
          fromUtc = today.minusDays(RAW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli(),
          toUtc = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
      _rawLoading.value = false
    }
  }

  /** One activity in full, from the documented single-activity endpoint. */
  fun fetchRawActivity(activityId: String) {
    val repository = intervalsRepository ?: return
    if (_rawLoading.value) return
    viewModelScope.launch {
      _rawLoading.value = true
      _rawError.value = null
      try {
        _rawResponse.value = repository.fetchRawActivity(activityId)
      } catch (e: IntervalsException) {
        _rawResponse.value = null
        _rawError.value = e.message
      }
      _rawLoading.value = false
    }
  }

  fun clearRawResponse() {
    _rawResponse.value = null
    _rawError.value = null
  }

  /**
   * What the watch recorded for each session that was actually done, keyed by session id — pace
   * included, which is the measurement Oura does not carry.
   */
  val runMetrics: StateFlow<Map<String, CompletedRunMetrics>> =
    (intervalsRepository?.observeMatchedRunMetrics()
        ?: MutableStateFlow(emptyMap<String, CompletedRunMetrics>()))
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  // ------------------------------------------------------------------ readiness advice

  /**
   * This morning's question about a poor readiness reading, or nothing.
   *
   * Recomputed whenever the plan or the stored Oura days change, because both halves of the rule
   * move: a session completed at nine answers yesterday's question, and a readiness score Oura
   * revised after the fact changes whether there was one.
   *
   * Dismissal is held here rather than in the database. It is a "not now", scoped to this reading
   * of this screen — the alternative, a persisted flag, would need its own table and its own
   * expiry, and a question that comes back tomorrow morning is the desired behaviour anyway.
   */
  private val _dismissedAdviceFor = MutableStateFlow<LocalDate?>(null)

  val readinessAdvice: StateFlow<ReadinessAdvice> =
    combine(
        repository.observeSessions(),
        currentDate.flatMapLatest { today ->
          ouraRepository.observeRecoveryRange(
            from = today.minusDays(ADVICE_DAYS_BACK),
            to = today,
          )
        },
        _dismissedAdviceFor,
        currentDate,
      ) { sessions, recovery, dismissedFor, today ->
        if (dismissedFor == today) ReadinessAdvice.None
        else readinessAdviceUseCase.execute(today, recovery, sessions)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadinessAdvice.None)

  /** "Ei nyt." Comes back tomorrow, deliberately. */
  fun dismissReadinessAdvice() {
    _dismissedAdviceFor.value = currentDate.value
  }

  /**
   * Moves the programme forward, exactly the way a missed session already moves it.
   *
   * The engine decides how far — one session goes to the next rest day, several shift the whole
   * plan — so accepting this offer does nothing the app could not already be asked to do; it only
   * asks at the moment the readiness number makes it worth asking.
   */
  fun shiftProgrammeForward() {
    viewModelScope.launch {
      engine.handleMissedSessions()
      dismissReadinessAdvice()
    }
  }

  /** Starts today lighter, through the same operation the "Kevyempi versio" button uses. */
  fun startTodayLighter() {
    val advice = readinessAdvice.value as? ReadinessAdvice.Offer ?: return
    viewModelScope.launch {
      advice.lightenableSessionIds.forEach { repository.applyLighterVersion(it, EventSource.ENGINE) }
      dismissReadinessAdvice()
    }
  }

  // ------------------------------------------------------------------ AI analysis

  /** Which providers have a key. No "verified" state — saving a key does not test it. */
  val analysisConfigured: StateFlow<Set<AnalysisProvider>> =
    analysisConnection?.configured
      ?: MutableStateFlow<Set<AnalysisProvider>>(emptySet()).asStateFlow()

  /** Provider whose last secure key write failed, if any. */
  val analysisSaveFailure: StateFlow<AnalysisProvider?> =
    analysisConnection?.saveFailure
      ?: MutableStateFlow<AnalysisProvider?>(null).asStateFlow()

  /** Which model the next analysis will ask. Read fresh per request, not captured at launch. */
  val analysisModel: StateFlow<AnalysisModel> =
    (analysisSettingsStore?.modelFlow ?: MutableStateFlow(AnalysisModel.DEFAULT))
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisModel.DEFAULT)

  /**
   * Every open analysis, keyed by session id.
   *
   * A map rather than one value, so scrolling the week list does not lose an open card and two
   * sessions can be read side by side. Nothing here is written to Room: an analysis lives as long as
   * this ViewModel and no longer, which is what keeps a feature that changes nothing from quietly
   * accumulating a history of machine verdicts beside the training log.
   */
  private val _aiAnalyses = MutableStateFlow<Map<String, AiAnalysisState>>(emptyMap())
  val aiAnalyses: StateFlow<Map<String, AiAnalysisState>> = _aiAnalyses.asStateFlow()

  fun saveAnalysisApiKey(provider: AnalysisProvider, key: String) {
    val connection = analysisConnection ?: return
    viewModelScope.launch { connection.saveApiKey(provider, key) }
  }

  /** Forgets one provider's key. No cached rows to drop — no analysis was ever stored. */
  fun clearAnalysisApiKey(provider: AnalysisProvider) {
    val connection = analysisConnection ?: return
    viewModelScope.launch { connection.clearApiKey(provider) }
  }

  fun setAnalysisModel(model: AnalysisModel) {
    val store = analysisSettingsStore ?: return
    viewModelScope.launch { store.setModel(model) }
  }

  /** Closes one card. The next tap asks again — nothing is cached to re-show. */
  fun dismissAiAnalysis(sessionId: String) {
    _aiAnalyses.update { it - sessionId }
  }

  /**
   * Asks the selected provider about one session, and puts the answer on that session's card.
   *
   * **Everything the prompt needs is read from the repositories, not from the screens' StateFlows** —
   * and that is a fix rather than a preference. The first version read `recoveryByDay.value`, which
   * is `stateIn(..., WhileSubscribed(5_000))`: it holds `emptyMap()` unless something is actively
   * collecting it, and only the Week screen ever does. Tapping the button on the Today screen
   * therefore sent a prompt containing **no recovery data at all** — no error, no empty field, just
   * an analysis quietly reasoning from less than the app knew. The model noticed, and said so; the
   * app did not.
   *
   * It would also have looked intermittent rather than broken: open the Week screen, come back
   * within five seconds, and the same tap works. `completedMetrics` and `runMetrics` were correct
   * only by luck — the Today screen happens to collect both.
   *
   * Reading from the source removes the class of bug rather than the instance. A prompt must not
   * depend on which screen is in front.
   */
  fun requestAiAnalysis(sessionId: String) {
    // The selected model decides which client answers. Read at request time rather than captured,
    // so changing the model in Settings takes effect on the next tap and not the next launch.
    val model = analysisModel.value
    val client = analysisClients[model.provider] ?: return
    if (_aiAnalyses.value[sessionId] is AiAnalysisState.Loading) return

    viewModelScope.launch {
      // The session comes from the database too, not from `workouts` — that is a StateFlow with the
      // same `WhileSubscribed` behaviour as the rest. It happens to be safe in the app, because the
      // button cannot be drawn without the list that holds it, but "safe because of what the UI
      // happens to do" is the reasoning that produced this bug in the first place.
      val session = repository.getSession(sessionId) ?: return@launch
      val date = runCatching { LocalDate.parse(session.scheduledDate) }.getOrNull() ?: return@launch
      val offset = ChronoUnit.DAYS.between(currentDate.value, date).toInt()
      // Decided from the same status and offset the button's visibility was, so the two cannot
      // disagree about what a session is.
      val kind = AiAnalysisAvailability.kindFor(session.status, offset) ?: return@launch

      // Set only once the request is known to be going out, so an ineligible tap leaves no spinner.
      _aiAnalyses.update { it + (sessionId to AiAnalysisState.Loading) }
      val prompt = buildAnalysisPrompt(kind, session, date)
      val state =
        try {
          AiAnalysisState.Loaded(client.analyse(prompt, model), prompt)
        } catch (e: AnalysisException) {
          AiAnalysisState.Failed(e.message ?: "AI-analyysi epäonnistui.", e.canRetry)
        }
      _aiAnalyses.update { it + (sessionId to state) }
    }
  }

  /**
   * One session's prompt, read straight from the database.
   *
   * The recovery window is asked for around **the session's own date**, not around today, and it
   * uses the builder's own [AnalysisPromptBuilder.TREND_DAYS_BACK] so the range fetched and the range
   * rendered cannot drift apart. For an upcoming session that means the week leading up to it; days
   * that have not happened yet simply have no rows, which is the honest answer rather than a gap to
   * paper over.
   */
  private suspend fun buildAnalysisPrompt(
    kind: AiAnalysisKind,
    session: TrainingSession,
    date: LocalDate,
  ): String {
    val recovery =
      ouraRepository
        .observeRecoveryRange(
          from = date.minusDays(AnalysisPromptBuilder.TREND_DAYS_BACK),
          to = date,
        )
        .first()
    val runs = intervalsRepository?.observeMatchedRunMetrics()?.first().orEmpty()

    return when (kind) {
      AiAnalysisKind.COMPLETED ->
        analysisPromptBuilder.completed(
          CompletedAnalysisInput(
            type = session.type,
            date = date,
            plannedDurationMin = session.durationMin?.takeIf { it > 0 },
            plannedIntensity = session.intensity,
            description = session.description,
            plannedRounds = session.rounds,
            exercises = session.exercises.orEmpty(),
            // Read from the completion event, not from this class's own map: the analysis can be
            // asked for days later, from a screen that never held the counter.
            guided = repository.guidedProgressFor(session.id),
            oura = ouraRepository.observeMatchedMetrics().first()[session.id],
            run = runs[session.id],
            recoveryByDay = recovery,
          )
        )

      AiAnalysisKind.UPCOMING ->
        analysisPromptBuilder.upcoming(
          UpcomingAnalysisInput(
            type = session.type,
            date = date,
            plannedDurationMin = session.durationMin?.takeIf { it > 0 },
            plannedIntensity = session.intensity,
            description = session.description,
            recoveryByDay = recovery,
            // From the daily series, not from the newest activity's own atl/ctl. Those are frozen
            // at the moment of a session and never decay, so a three-day-old run reported a fatigue
            // the athlete had already shed — measured here as a TSB of -5.9 against a true -0.6.
            load = intervalsRepository?.loadOn(date),
          )
        )
    }
  }

  /** Cheap enough to run whenever Settings opens: one GET of a few hundred bytes. */
  fun checkForUpdate() {
    if (_updateStatus.value is UpdateStatus.Checking) return
    viewModelScope.launch {
      _updateStatus.value = UpdateStatus.Checking
      _updateStatus.value = checkForUpdateUseCase.execute()
    }
  }

  private val _missedSessionsProposal =
    MutableStateFlow<MissedSessionsProposal>(MissedSessionsProposal.None)
  val missedSessionsProposal: StateFlow<MissedSessionsProposal> =
    _missedSessionsProposal.asStateFlow()

  /**
   * The day the user last said "not now", so that answer survives leaving the screen.
   *
   * The same mechanism as [_dismissedAdviceFor], and here for the same reason. Without it,
   * rejecting was worth nothing: [checkMissedSessions] runs on every resume of the Today screen, so
   * the card came straight back the next time the screen was opened. A refusal the app forgets in
   * seconds is not a refusal, it is a nag.
   *
   * Keyed by date rather than by proposal, so a *changed* situation does not get through on a
   * technicality — if another session goes missed today, the answer for today still stands. It
   * expires at the next plan-zone midnight, which is what makes this "not today" rather than
   * "never".
   */
  private val _missedProposalDismissedFor = MutableStateFlow<LocalDate?>(null)

  /**
   * Refreshes a read-only proposal. Calling this never writes the calendar.
   *
   * The refusal is read from [missedProposalDismissalStore] when this process has not seen one
   * yet, which is what makes "ei nyt" outlive an app update. Both reads happen inside the
   * coroutine, before the proposal is published, so a slow DataStore cannot let the card flash up
   * on a launch where it had already been answered.
   */
  fun checkMissedSessions() {
    if (_missedProposalDismissedFor.value == currentDate.value) return
    viewModelScope.launch {
      val dismissedFor =
        _missedProposalDismissedFor.value
          ?: missedProposalDismissalStore?.dismissedFor()?.also {
            _missedProposalDismissedFor.value = it
          }
      if (dismissedFor == currentDate.value) return@launch
      _missedSessionsProposal.value = engine.proposeMissedSessions()
    }
  }

  /**
   * "Nämä on tehty." Closes the missed sessions where they are instead of moving them.
   *
   * The card's other two buttons both assume the training is still ahead of you: one moves the
   * whole programme forward, the other changes nothing and lets the card return tomorrow. Neither
   * fits a backlog of sessions that were never going to be done — the plan rows left behind while
   * the app itself was being written — and this is the answer for those.
   */
  fun completeMissedSessionsProposal() {
    val proposal = _missedSessionsProposal.value
    if (proposal == MissedSessionsProposal.None) return
    _missedSessionsProposal.value = MissedSessionsProposal.None
    viewModelScope.launch { engine.completeMissedSessions(proposal) }
  }

  /** Applies the preview the user saw; a stale or twice-accepted preview is rejected by the engine. */
  fun acceptMissedSessionsProposal() {
    val proposal = _missedSessionsProposal.value
    if (proposal == MissedSessionsProposal.None) return
    _missedSessionsProposal.value = MissedSessionsProposal.None
    viewModelScope.launch { engine.applyMissedSessions(proposal) }
  }

  /**
   * "Ei nyt." Writes no calendar change, and — unlike before — is still true after a restart.
   *
   * The date is persisted as well as remembered: the in-memory answer died with the process, so
   * installing a new build re-asked about the same sessions minutes after they had been refused.
   */
  fun rejectMissedSessionsProposal() {
    val today = currentDate.value
    _missedProposalDismissedFor.value = today
    _missedSessionsProposal.value = MissedSessionsProposal.None
    viewModelScope.launch { missedProposalDismissalStore?.setDismissedFor(today) }
  }

  /** Opens the guide sheet for one movement and starts the lookup. */
  fun openExerciseGuide(exercise: Exercise) {
    guideExercise = exercise
    _guideState.value = ExerciseGuideState.Loading(exercise.name)
    viewModelScope.launch {
      // A sheet closed or reopened while the request was in flight must not be overwritten by
      // the answer to the previous question.
      val state = loadExerciseGuideUseCase.execute(exercise)
      if (guideExercise == exercise) _guideState.value = state
    }
  }

  fun retryExerciseGuide() {
    guideExercise?.let { openExerciseGuide(it) }
  }

  /**
   * Shows the guide the user picked from the suggestion list.
   *
   * No request: the search already returned the whole thing. It stays marked as a suggestion —
   * picking one does not make it what the plan meant.
   */
  fun selectGuideSuggestion(guide: ExerciseGuide) {
    val name = _guideState.value?.exerciseName ?: return
    _guideState.value = ExerciseGuideState.Loaded(name, guide, suggested = true)
  }

  fun closeExerciseGuide() {
    guideExercise = null
    _guideState.value = null
  }

  /**
   * How far each guided workout on screen has got, keyed by session id.
   *
   * **Mirrored from the screen rather than owned here.** The card keeps its own `rememberSaveable`
   * counter — that is what survives the process being killed mid-workout — and reports every change
   * up. This map exists so that pressing "Valmis" has something to write down: the counter's own
   * scope ends with the composition, and the completion outlives it.
   *
   * Not persisted: a session still open when the app is killed keeps its count in the card's saved
   * state, not here, and one that is finished has already had the count written to its completion
   * event.
   */
  private val guidedProgress = MutableStateFlow<Map<String, GuidedProgress>>(emptyMap())

  fun recordGuidedProgress(sessionId: String, progress: GuidedProgress) {
    guidedProgress.update { it + (sessionId to progress) }
  }

  fun updateWorkoutStatus(workoutId: String, newStatus: SessionStatus) {
    viewModelScope.launch {
      when (newStatus) {
        SessionStatus.REPLACED_WITH_LIGHTER_VERSION -> repository.applyLighterVersion(workoutId)
        // The one transition that has more to say than its own name: what was ticked off on the
        // way there. Anything else, including a session with no guided list, completes as before.
        SessionStatus.COMPLETED ->
          repository.completeGuided(workoutId, guidedProgress.value[workoutId])
        else -> repository.transition(workoutId, newStatus)
      }
    }
  }

  fun moveWorkoutToTomorrow(workoutId: String) {
    viewModelScope.launch {
      val session = repository.getSession(workoutId) ?: return@launch
      val newDate = LocalDate.parse(session.scheduledDate).plusDays(1)
      repository.reschedule(workoutId, newDate)
      rescheduleAlarmsUseCase.execute()
    }
  }

  fun markSick() {
      viewModelScope.launch {
          engine.markSick()
      }
  }

  fun markRecovered() {
      viewModelScope.launch {
          engine.markRecovered()
      }
  }

  fun updateNotificationTime(type: WorkoutType, newTime: String) {
    viewModelScope.launch {
      settingsStore.updateTime(type, newTime)
      rescheduleAlarmsUseCase.execute()
    }
  }

  /**
   * Imports a plan from raw JSON text — the same path for a picked file and for the clipboard.
   *
   * @param startToday move the whole plan so its first day is today, instead of using the dates
   *   written in the file.
   */
  fun importPlanJson(rawJson: String?, startToday: Boolean = false) {
    if (rawJson.isNullOrBlank()) {
      _importFeedback.value =
        ImportFeedback("Ei tuotavaa", "Tiedosto tai leikepöytä oli tyhjä.", isError = true)
      return
    }
    viewModelScope.launch { runImport(rawJson, startToday, confirmed = false) }
  }

  /**
   * The user has read what the import would do to the plan already stored and said yes.
   *
   * The document is held rather than re-read: the file picker's permission is good for one read,
   * and asking for the same file twice is not something a confirmation dialog should require.
   */
  fun confirmPendingImport() {
    val pending = _pendingImport.value ?: return
    _pendingImport.value = null
    viewModelScope.launch { runImport(pending.rawJson, pending.startToday, confirmed = true) }
  }

  fun cancelPendingImport() {
    _pendingImport.value = null
  }

  private suspend fun runImport(rawJson: String, startToday: Boolean, confirmed: Boolean) {
    val result = repository.importPlan(rawJson, startToday = startToday, confirmed = confirmed)
    if (result is ImportResult.NeedsConfirmation) {
      _pendingImport.value =
        PendingImportPrompt(rawJson, startToday, result.planName, result.action)
      return
    }
    if (result is ImportResult.Success) {
      rescheduleAlarmsUseCase.execute()
    }
    _importFeedback.value = result.toFeedback()
  }

  fun resetSampleData() {
    viewModelScope.launch {
      val success = repository.resetSampleData()
      if (success) {
        rescheduleAlarmsUseCase.execute()
        _importFeedback.value = ImportFeedback("Onnistui", "Esimerkkidata palautettu (aloitus tänään).", isError = false)
      } else {
        _importFeedback.value = ImportFeedback("Virhe", "Esimerkkidatan palautus epäonnistui.", isError = true)
      }
    }
  }

  fun dismissImportFeedback() {
    _importFeedback.value = null
  }

  private fun ImportResult.toFeedback(): ImportFeedback =
    when (this) {
      is ImportResult.Success ->
        ImportFeedback(
          title = "Suunnitelma tuotu",
          detail = "\"$planName\" — $sessionCount harjoitusta.",
          isError = false,
        )
      is ImportResult.Unreadable ->
        ImportFeedback("Tiedostoa ei voitu lukea", message, isError = true)
      is ImportResult.Invalid ->
        ImportFeedback(
          title = "Suunnitelmassa on ${errors.size} virhettä",
          detail = errors.joinToString("\n") { it.toString() },
          isError = true,
        )
      is ImportResult.AlreadyImported ->
        ImportFeedback(
          title = "Jo tuotu",
          detail = "Suunnitelma \"$planName\" on jo tuotu sellaisenaan.",
          isError = false,
        )
      // Never reaches here: runImport turns it into a dialog before feedback is produced.
      is ImportResult.NeedsConfirmation ->
        ImportFeedback("Vahvistus tarvitaan", "Tuonti odottaa vahvistusta.", isError = false)
      is ImportResult.Conflict ->
        ImportFeedback(
          title = "Ristiriita olemassa olevien tietojen kanssa",
          detail =
            if (planId != null) {
              "Suunnitelma tunnisteella \"$planId\" on jo olemassa, mutta sisältö eroaa. " +
                "Poista vanha suunnitelma ensin, jos haluat korvata sen."
            } else {
              "Nämä harjoitustunnisteet ovat jo käytössä: " +
                conflictingSessionIds.joinToString(", ")
            },
          isError = true,
        )
    }

  companion object {
    /**
     * How far back a foreground sync reaches.
     *
     * More than today, because Oura revises a day after the fact and a phone that was offline over
     * a weekend would otherwise keep a permanent hole in it.
     */
    private const val SYNC_DAYS = 14L

    /** As far back as the calendar shows, so a day there can list what Oura holds for it. */
    private const val CALENDAR_DAYS_BACK = 28L

    /** The advice rule reads today and yesterday; a couple of days is margin, not a window. */
    private const val ADVICE_DAYS_BACK = 3L

    /**
     * How far back the raw-data screen reaches.
     *
     * Shorter than [SYNC_DAYS] on purpose: that request names eighteen fields, this one names none
     * and gets all 183 per activity, so a week is already a long document to read.
     */
    private const val RAW_DAYS = 7L

    /** Statuses that describe a closed row and are never drawn on the Today/Week screens. */
    private val HIDDEN_STATUSES = setOf(SessionStatus.RESCHEDULED, SessionStatus.CANCELLED)

    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val application =
          this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as TreenivalmentajaApplication
        WorkoutViewModel(
          application.repository,
          application.engine,
          application.settingsStore,
          application.rescheduleAlarmsUseCase,
          application.checkForUpdateUseCase,
          application.loadExerciseGuideUseCase,
          application.ouraConnection,
          application.ouraRepository,
          application.intervalsConnection,
          application.intervalsRepository,
          analysisConnection = application.analysisConnection,
          analysisClients = application.analysisClients,
          analysisSettingsStore = application.analysisSettingsStore,
          analysisPromptBuilder = application.analysisPromptBuilder,
          missedProposalDismissalStore = application.missedProposalDismissalStore,
        )
      }
    }

    internal fun List<TrainingSession>.toWorkouts(today: LocalDate): List<Workout> =
      filterNot { it.status in HIDDEN_STATUSES }
        .map { session ->
          Workout(
            id = session.id,
            dayOffset =
              ChronoUnit.DAYS.between(today, LocalDate.parse(session.scheduledDate)).toInt(),
            type = session.type,
            time = session.scheduledTime ?: "Ei aikaa",
            durationMin = session.durationMin ?: 0,
            description = session.description.orEmpty(),
            status = session.status,
            appliedLighterVariant = session.appliedLighterVariant,
            movedHere = session.originalSessionId != null,
            exercises = session.exercises.orEmpty(),
            rounds = (session.rounds ?: session.roundsMin ?: 1).coerceAtLeast(1),
            intensity = session.intensity,
          )
        }
  }
}
