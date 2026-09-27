package fi.merilainen.treenivalmentaja.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Also records a successful fetch with no HR; failures never create a row. No GPS is retained. */
@Entity(tableName = "intervals_run_traces")
data class IntervalsTraceEntity(@PrimaryKey val activityId: String, val traceJson: String, val fetchedAtUtc: Long)
