package fi.merilainen.treenivalmentaja

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.merilainen.treenivalmentaja.data.importer.ImportResult
import fi.merilainen.treenivalmentaja.data.repository.TrainingRepository
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.TrainingEngine
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

enum class RecoveryState(val title: String, val message: String) {
  GOOD("Palautuminen: Hyvä", "Tee suunnitelman mukaan"),
  OKAY("Palautuminen: Kohtalainen", "Kevyempi versio voi olla järkevä"),
  POOR("Palautuminen: Heikko", "Harkitse lepoa"),
}

data class NotificationSettings(
  val runningTime: String = "16:00",
  val strengthTime: String = "07:00",
  val skiingTime: String = "10:00",
)

/** Result of the last plan import, shown once and then dismissed. */
data class ImportFeedback(val title: String, val detail: String, val isError: Boolean)

class WorkoutViewModel(
  private val repository: TrainingRepository,
  private val engine: TrainingEngine
) : ViewModel() {

  val workouts: StateFlow<List<Workout>> =
    repository
      .observeSessions()
      .map { sessions -> sessions.toWorkouts(LocalDate.now()) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _recoveryState = MutableStateFlow(RecoveryState.OKAY)
  val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

  private val _notificationSettings = MutableStateFlow(NotificationSettings())
  val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings.asStateFlow()

  private val _importFeedback = MutableStateFlow<ImportFeedback?>(null)
  val importFeedback: StateFlow<ImportFeedback?> = _importFeedback.asStateFlow()

  init {
    viewModelScope.launch { repository.seedIfEmpty() }
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
    _notificationSettings.update { current ->
      when (type) {
        WorkoutType.RUNNING -> current.copy(runningTime = newTime)
        WorkoutType.STRENGTH -> current.copy(strengthTime = newTime)
        WorkoutType.SKIING -> current.copy(skiingTime = newTime)
      }
    }
  }

  /** Imports a plan from raw JSON text — the same path for a picked file and for the clipboard. */
  fun importPlanJson(rawJson: String?) {
    if (rawJson.isNullOrBlank()) {
      _importFeedback.value =
        ImportFeedback("Ei tuotavaa", "Tiedosto tai leikepöytä oli tyhjä.", isError = true)
      return
    }
    viewModelScope.launch { _importFeedback.value = repository.importPlan(rawJson).toFeedback() }
  }

  fun resetSampleData() {
    viewModelScope.launch {
      val success = repository.resetSampleData()
      if (success) {
        _importFeedback.value = ImportFeedback("Onnistui", "Esimerkkidata palautettu (aloitus tänään).", isError = false)
        _recoveryState.value = RecoveryState.OKAY
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
        WorkoutViewModel(application.repository, application.engine)
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
            time = session.scheduledTime,
            durationMin = session.durationMin ?: 0,
            description = session.description.orEmpty(),
            status = session.status,
            appliedLighterVariant = session.appliedLighterVariant,
            movedHere = session.originalSessionId != null,
          )
        }
  }
}
