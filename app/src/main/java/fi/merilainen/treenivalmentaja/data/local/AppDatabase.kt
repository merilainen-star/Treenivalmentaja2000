package fi.merilainen.treenivalmentaja.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
  version = 4,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun trainingPlanDao(): TrainingPlanDao
  abstract fun workoutSessionDao(): WorkoutSessionDao
  abstract fun sessionEventDao(): SessionEventDao
  abstract fun ouraDao(): OuraDao

  companion object {
    private const val DB_NAME = "treenivalmentaja.db"

    
    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF;")
        
        // 1. Create new table
        db.execSQL("""
          CREATE TABLE IF NOT EXISTS `workout_sessions_new` (
            `id` TEXT NOT NULL, 
            `planId` TEXT NOT NULL, 
            `type` TEXT NOT NULL, 
            `weekNumber` INTEGER NOT NULL, 
            `scheduledDate` TEXT NOT NULL, 
            `scheduledTime` TEXT, 
            `remindAtUtc` INTEGER NOT NULL, 
            `timeIsFixed` INTEGER NOT NULL DEFAULT 0, 
            `reminderOverride` TEXT, 
            `durationMin` INTEGER, 
            `distanceKm` REAL, 
            `intensity` TEXT, 
            `rounds` INTEGER, 
            `roundsMin` INTEGER, 
            `roundsMax` INTEGER, 
            `targetPace` TEXT, 
            `warmupSec` INTEGER, 
            `exercisesJson` TEXT, 
            `lighterAlternativeJson` TEXT, 
            `description` TEXT, 
            `status` TEXT NOT NULL, 
            `appliedLighterVariant` INTEGER NOT NULL, 
            `originalSessionId` TEXT, 
            `updatedAt` INTEGER NOT NULL, 
            PRIMARY KEY(`id`), 
            FOREIGN KEY(`planId`) REFERENCES `training_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
        """)

        // 2. Copy data
        db.execSQL("""
          INSERT INTO `workout_sessions_new` (
            `id`, `planId`, `type`, `weekNumber`, `scheduledDate`, `scheduledTime`, 
            `remindAtUtc`, `timeIsFixed`, `reminderOverride`, `durationMin`, `distanceKm`, 
            `intensity`, `rounds`, `roundsMin`, `roundsMax`, `targetPace`, `warmupSec`, 
            `exercisesJson`, `lighterAlternativeJson`, `description`, `status`, 
            `appliedLighterVariant`, `originalSessionId`, `updatedAt`
          )
          SELECT 
            `id`, `planId`, `type`, `weekNumber`, `scheduledDate`, `scheduledTime`, 
            `scheduledAtUtc`, 0, NULL, `durationMin`, `distanceKm`, 
            `intensity`, `rounds`, `roundsMin`, `roundsMax`, `targetPace`, `warmupSec`, 
            `exercisesJson`, `lighterAlternativeJson`, `description`, `status`, 
            `appliedLighterVariant`, `originalSessionId`, `updatedAt`
          FROM `workout_sessions`
        """)

        // 3. Drop old and rename
        db.execSQL("DROP TABLE `workout_sessions`")
        db.execSQL("ALTER TABLE `workout_sessions_new` RENAME TO `workout_sessions`")

        // Recreate indices
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_planId` ON `workout_sessions` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_originalSessionId` ON `workout_sessions` (`originalSessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_scheduledDate` ON `workout_sessions` (`scheduledDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_remindAtUtc` ON `workout_sessions` (`remindAtUtc`)")
        
        db.execSQL("PRAGMA foreign_keys=ON;")
      }
    }


    @Volatile private var instance: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase =
      instance
        ?: synchronized(this) {
          instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): AppDatabase =
      Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
        .addMigrations(MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
        .build()
  }
}
