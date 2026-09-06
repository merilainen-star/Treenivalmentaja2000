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

  /**
   * A short list of whole numbers in one column, as `123,145,160`.
   *
   * Used for the heart-rate zone table and its times — five numbers each, always read and written
   * together, and meaningless apart. A child table would give them a join and an ordering rule for
   * no gain. Nothing longer or less uniform belongs here.
   *
   * An empty list round-trips as an empty list rather than as `null`: "intervals.icu sent no zones"
   * and "intervals.icu sent an empty zone table" are different facts, and only the first is `null`.
   */
  @TypeConverter fun intListToString(value: List<Int>?): String? = value?.joinToString(",")

  @TypeConverter
  fun stringToIntList(value: String?): List<Int>? =
    value?.let { raw ->
      if (raw.isBlank()) emptyList()
      // A malformed entry is dropped rather than read as zero: a zone boundary of 0 bpm would be a
      // measurement, and this is the absence of one.
      else raw.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}
