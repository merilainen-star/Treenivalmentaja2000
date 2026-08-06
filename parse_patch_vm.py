import re

with open("app/src/main/java/fi/merilainen/treenivalmentaja/WorkoutViewModel.kt", "r") as f:
    content = f.read()

# Replace properties and dependencies
init_sig = """class WorkoutViewModel(
  private val repository: TrainingRepository,
  private val engine: TrainingEngine,
  private val settingsStore: fi.merilainen.treenivalmentaja.data.settings.NotificationSettingsStore,
  private val rescheduleAlarmsUseCase: fi.merilainen.treenivalmentaja.domain.RescheduleAlarmsUseCase
) : ViewModel() {"""

content = re.sub(r'class WorkoutViewModel\([\s\S]*?\) : ViewModel\(\) \{', init_sig, content)

# update notificationSettings stateflow
notif = """  val notificationSettings: StateFlow<fi.merilainen.treenivalmentaja.data.settings.NotificationSettings> =
    settingsStore.settingsFlow.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5_000),
      fi.merilainen.treenivalmentaja.data.settings.NotificationSettings()
    )"""

content = re.sub(r'  private val _notificationSettings = MutableStateFlow.*?asStateFlow\(\)', notif, content, flags=re.DOTALL)

# updateNotificationTime
update_time = """  fun updateNotificationTime(type: WorkoutType, newTime: String) {
    viewModelScope.launch {
      settingsStore.updateTime(type, newTime)
      rescheduleAlarmsUseCase.execute()
    }
  }"""

content = re.sub(r'  fun updateNotificationTime\(type: WorkoutType, newTime: String\) \{[\s\S]*?    \}\n  \}', update_time, content)

# update moveWorkoutToTomorrow
move_workout = """  fun moveWorkoutToTomorrow(workoutId: String) {
    viewModelScope.launch {
      val session = repository.getSession(workoutId) ?: return@launch
      val newDate = LocalDate.parse(session.scheduledDate).plusDays(1)
      repository.reschedule(workoutId, newDate)
      rescheduleAlarmsUseCase.execute()
    }
  }"""

content = re.sub(r'  fun moveWorkoutToTomorrow\(workoutId: String\) \{[\s\S]*?    \}\n  \}', move_workout, content)

# update importPlanJson
import_plan = """  fun importPlanJson(rawJson: String?) {
    if (rawJson.isNullOrBlank()) {
      _importFeedback.value =
        ImportFeedback("Ei tuotavaa", "Tiedosto tai leikepöytä oli tyhjä.", isError = true)
      return
    }
    viewModelScope.launch {
      val result = repository.importPlan(rawJson)
      if (result is ImportResult.Success) {
        rescheduleAlarmsUseCase.execute()
      }
      _importFeedback.value = result.toFeedback()
    }
  }"""

content = re.sub(r'  fun importPlanJson\(rawJson: String\?\) \{[\s\S]*?toFeedback\(\) \}\n  \}', import_plan, content)

# update resetSampleData
reset_sample = """  fun resetSampleData() {
    viewModelScope.launch {
      val success = repository.resetSampleData()
      if (success) {
        rescheduleAlarmsUseCase.execute()
        _importFeedback.value = ImportFeedback("Onnistui", "Esimerkkidata palautettu (aloitus tänään).", isError = false)
        _recoveryState.value = RecoveryState.OKAY
      } else {
        _importFeedback.value = ImportFeedback("Virhe", "Esimerkkidatan palautus epäonnistui.", isError = true)
      }
    }
  }"""
content = re.sub(r'  fun resetSampleData\(\) \{[\s\S]*?    \}\n  \}', reset_sample, content)

# update Factory
factory = """    val Factory: ViewModelProvider.Factory = viewModelFactory {
      initializer {
        val application =
          this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as TreenivalmentajaApplication
        WorkoutViewModel(
          application.repository,
          application.engine,
          application.settingsStore,
          application.rescheduleAlarmsUseCase
        )
      }
    }"""
content = re.sub(r'    val Factory: ViewModelProvider\.Factory = viewModelFactory \{[\s\S]*?    \}', factory, content)

# Remove old NotificationSettings data class
content = re.sub(r'data class NotificationSettings\([\s\S]*?\)\n', '', content)

with open("app/src/main/java/fi/merilainen/treenivalmentaja/WorkoutViewModel.kt", "w") as f:
    f.write(content)
