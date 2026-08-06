package fi.merilainen.treenivalmentaja.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class NotificationSettings(
  val runningTime: String = "16:00",
  val strengthTime: String = "07:00",
  val skiingTime: String = "10:00",
  val reminderOffsetMin: Int = 0
) {
  fun getTimeForType(type: WorkoutType): String =
    when (type) {
      WorkoutType.RUNNING -> runningTime
      WorkoutType.STRENGTH -> strengthTime
      WorkoutType.SKIING -> skiingTime
    }
}

class NotificationSettingsStore(private val context: Context) {
  private val RUNNING_TIME = stringPreferencesKey("running_time")
  private val STRENGTH_TIME = stringPreferencesKey("strength_time")
  private val SKIING_TIME = stringPreferencesKey("skiing_time")
  private val REMINDER_OFFSET_MIN = intPreferencesKey("reminder_offset_min")
  private val ALARM_COUNT = intPreferencesKey("alarm_count")

  val alarmCountFlow: Flow<Int> = context.dataStore.data.map { it[ALARM_COUNT] ?: 0 }

  val settingsFlow: Flow<NotificationSettings> = context.dataStore.data.map { preferences ->
    NotificationSettings(
      runningTime = preferences[RUNNING_TIME] ?: "16:00",
      strengthTime = preferences[STRENGTH_TIME] ?: "07:00",
      skiingTime = preferences[SKIING_TIME] ?: "10:00",
      reminderOffsetMin = preferences[REMINDER_OFFSET_MIN] ?: 0
    )
  }

  suspend fun updateTime(type: WorkoutType, time: String) {
    context.dataStore.edit { preferences ->
      when (type) {
        WorkoutType.RUNNING -> preferences[RUNNING_TIME] = time
        WorkoutType.STRENGTH -> preferences[STRENGTH_TIME] = time
        WorkoutType.SKIING -> preferences[SKIING_TIME] = time
      }
    }
  }

  suspend fun updateAlarmCount(count: Int) {
    context.dataStore.edit { it[ALARM_COUNT] = count }
  }

  suspend fun updateReminderOffset(offsetMin: Int) {
    context.dataStore.edit { preferences ->
      preferences[REMINDER_OFFSET_MIN] = offsetMin
    }
  }
}
