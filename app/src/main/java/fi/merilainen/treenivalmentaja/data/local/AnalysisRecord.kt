package fi.merilainen.treenivalmentaja.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity

enum class StoredAnalysisState { IN_FLIGHT, LOADED, FAILED }

/** Persistent result and request receipt; IN_FLIGHT survives process death to prevent rebilling. */
@Entity(tableName = "session_analyses", primaryKeys = ["sessionId", "kind"], foreignKeys = [
  ForeignKey(entity = WorkoutSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
])
data class AnalysisRecord(
  val sessionId: String,
  val kind: String,
  val state: StoredAnalysisState,
  val text: String,
  val prompt: String,
  val model: String,
  val createdAtUtc: Long,
)

@Dao
interface AnalysisDao {
  @Query("SELECT * FROM session_analyses") fun observe(): Flow<List<AnalysisRecord>>
  @Query("SELECT * FROM session_analyses WHERE sessionId = :id AND kind = :kind")
  suspend fun get(id: String, kind: String): AnalysisRecord?
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: AnalysisRecord)
}
