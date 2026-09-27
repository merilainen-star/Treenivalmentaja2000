package fi.merilainen.treenivalmentaja.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Keep the exact lap tables already shipped in release 45fad49 (Room version 17). */
@Entity(tableName = "intervals_run_laps", primaryKeys = ["activityId", "lapIndex"])
data class IntervalsRunLapEntity(
  val activityId: String,
  val lapIndex: Int,
  val durationMs: Long,
  val distanceMeters: Double? = null,
  val avgHeartRate: Int? = null,
  val maxHeartRate: Int? = null,
)

@Entity(tableName = "intervals_lap_fetches")
data class IntervalsLapFetchEntity(
  @PrimaryKey val activityId: String,
  val lapCount: Int,
  val fetchedAtUtc: Long,
)
