package fi.merilainen.treenivalmentaja.data.local

import androidx.room.TypeConverter
import fi.merilainen.treenivalmentaja.domain.EventSource
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType

/**
 * Enums are stored as their `name`, not their ordinal. Reordering the enum must never silently
 * change the meaning of existing rows.
 */
class Converters {
  @TypeConverter fun sessionStatusToString(value: SessionStatus?): String? = value?.name

  @TypeConverter
  fun stringToSessionStatus(value: String?): SessionStatus? = value?.let(SessionStatus::valueOf)

  @TypeConverter fun workoutTypeToString(value: WorkoutType?): String? = value?.name

  @TypeConverter
  fun stringToWorkoutType(value: String?): WorkoutType? = value?.let(WorkoutType::valueOf)

  @TypeConverter fun eventSourceToString(value: EventSource?): String? = value?.name

  @TypeConverter
  fun stringToEventSource(value: String?): EventSource? = value?.let(EventSource::valueOf)
}
