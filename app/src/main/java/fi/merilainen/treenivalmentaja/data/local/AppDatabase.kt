package fi.merilainen.treenivalmentaja.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fi.merilainen.treenivalmentaja.data.local.dao.OuraDao
import fi.merilainen.treenivalmentaja.data.local.dao.SessionEventDao
import fi.merilainen.treenivalmentaja.data.local.dao.TrainingPlanDao
import fi.merilainen.treenivalmentaja.data.local.dao.WorkoutSessionDao
import fi.merilainen.treenivalmentaja.data.local.entity.OuraDailySummaryEntity
import fi.merilainen.treenivalmentaja.data.local.entity.OuraWorkoutEntity
import fi.merilainen.treenivalmentaja.data.local.entity.SessionEventEntity
import fi.merilainen.treenivalmentaja.data.local.entity.TrainingPlanEntity
import fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity

@Database(
  entities =
    [
      TrainingPlanEntity::class,
      WorkoutSessionEntity::class,
      SessionEventEntity::class,
      OuraDailySummaryEntity::class,
      OuraWorkoutEntity::class,
    ],
  version = 3,
  exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun trainingPlanDao(): TrainingPlanDao

  abstract fun workoutSessionDao(): WorkoutSessionDao

  abstract fun sessionEventDao(): SessionEventDao

  abstract fun ouraDao(): OuraDao

  companion object {
    private const val DB_NAME = "treenivalmentaja.db"

    @Volatile private var instance: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase =
      instance
        ?: synchronized(this) {
          instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): AppDatabase =
      Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
        .fallbackToDestructiveMigration()
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
        .build()
  }
}
