package fi.merilainen.treenivalmentaja

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.merilainen.treenivalmentaja.data.importer.ImportResult
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettings
import fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore
import fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.data.guide.ExerciseGuide
import fi.merilainen.treenivalmentaja.domain.CheckForUpdateUseCase
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseGuideState
import fi.merilainen.treenivalmentaja.domain.LoadExerciseGuideUseCase
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
import fi.merilainen.treenivalmentaja.domain.UpdateStatus
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
) {
  val dayString: String
    get() =
      when (dayOffset) {
        0 -> "Tänään"
        1 -> "Huomenna"
        2 -> "Keskiviikko"
        3 -> "Torstai"
        4 -> "Perjantai"
        5 -> "Lauantai"
        6 -> "Sunnuntai"
        else -> "Päivä $dayOffset"
      }
}

// There was a RecoveryState here, with three readings and their advice. Nothing ever produced
// anything but the middle one, so the Today screen showed the same verdict every day. It comes
// back when Oura can decide between them; until then the app says nothing about recovery rather
// than saying the same wrong-shaped thing daily. See docs/ROADMAP.md.


/** Result of the last plan import, shown once and then dismissed. */
data class ImportFeedback(val title: String, val detail: String, val isError: Boolean)

class WorkoutViewModel(
  private val repository: TrainingRepository,
  private val engine: TrainingEngine,
  private val settingsStore: NotificationSettingsStore,
  private val rescheduleAlarmsUseCase: RescheduleAlarmsUseCase,
  private val checkForUpdateUseCase: CheckForUpdateUseCase,
  private val loadExerciseGuideUseCase: LoadExerciseGuideUseCase,
) : ViewModel() {

  val workouts: StateFlow<List<Workout>> =
    repository
      .observeSessions()
      .map { sessions -> sessions.toWorkouts(LocalDate.now()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val notificationSettings: StateFlow<NotificationSettings> =
    settingsStore.settingsFlow.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5_000),
      NotificationSettings()
    )

  private val _importFeedback = MutableStateFlow<ImportFeedback?>(null)
  val importFeedback: StateFlow<ImportFeedback?> = _importFeedback.asStateFlow()

  private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
  val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

  /** `null` means no guide sheet is open. */
  private val _guideState = MutableStateFlow<ExerciseGuideState?>(null)
  val guideState: StateFlow<ExerciseGuideState?> = _guideState.asStateFlow()

  /** The exercise the open sheet is about, so "Yritä uudelleen" knows what to retry. */
  private var guideExercise: Exercise? = null

  init {
    viewModelScope.launch {
      // Seeding only writes when the database is empty, so it cannot disturb an imported plan.
      repository.seedIfEmpty()
      // Neither can this: it removes plans an import has already replaced, which no screen reads.
      // It is here rather than only in the importer so that phones carrying plans from before
      // imports deleted them are cleaned up without having to import again.
      repository.deleteReplacedPlans()
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

  /**
   * Reschedules sessions that were not done. **Never called on startup** — see below.
   *
   * It used to run from `init`, which meant every launch, including the one right after an app
   * update, rewrote the calendar. With a plan imported from a file whose dates had already
   * passed, `TrainingEngine.handleMissedSessions` saw every past session as missed and applied
   * its bulk-shift rule to all of them, moving the whole programme so that week 1 landed on
   * today. An eight-week plan in its fourth week silently restarted from the beginning, and
   * every session came back marked as moved.
   *
   * Installing a new build must not change what is in the calendar. Rescheduling is a training
   * decision, so it needs a training decision to trigger it — this is left for an explicit
   * action in the UI, which does not exist yet.
   */
  fun checkMissedSessions() {
    viewModelScope.launch {
      engine.handleMissedSessions()
    }
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

  fun updateWorkoutStatus(workoutId: String, newStatus: SessionStatus) {
    viewModelScope.launch {
      if (newStatus == SessionStatus.REPLACED_WITH_LIGHTER_VERSION) {
        repository.applyLighterVersion(workoutId)
      } else {
        repository.transition(workoutId, newStatus)
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
    viewModelScope.launch {
      val result = repository.importPlan(rawJson, startToday = startToday)
      if (result is ImportResult.Success) {
        rescheduleAlarmsUseCase.execute()
      }
      _importFeedback.value = result.toFeedback()
    }
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
          )
        }
  }
}
