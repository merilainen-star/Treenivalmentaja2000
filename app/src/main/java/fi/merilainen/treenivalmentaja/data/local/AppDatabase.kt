package fi.merilainen.treenivalmentaja.data.local

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec
import fi.merilainen.treenivalmentaja.data.local.dao.IntervalsDao
import fi.merilainen.treenivalmentaja.data.local.dao.OuraDao
import fi.merilainen.treenivalmentaja.data.local.dao.SessionEventDao
import fi.merilainen.treenivalmentaja.data.local.dao.TrainingPlanDao
import fi.merilainen.treenivalmentaja.data.local.dao.WorkoutSessionDao
import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsActivityEntity
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
      IntervalsActivityEntity::class,
    ],
  version = 8,
  exportSchema = true,
  // 4→5 added three nullable columns on `oura_workouts` and 5→6 added a whole table, both purely
  // additive. 6→7 is the one that removes something: `strava_activities` goes and
  // `intervals_activities` arrives, because Strava paywalled its API and the Suunto data now comes
  // through intervals.icu instead. A drop is still an auto migration when it is declared —
  // [DropStravaActivities] is what tells Room the table is meant to go rather than to be renamed.
  //
  // **Nothing is lost by that drop.** Strava was never connected to a real account, so the table
  // it created has always been empty on every device this build reaches. Were that not true, this
  // would be a hand-written migration copying rows across instead.
  // 7→8 is additive again: two nullable columns on `intervals_activities` (`avgCadence`,
  // `intensity`), so a row stored before them keeps its values and gets nulls.
  autoMigrations =
    [
      AutoMigration(from = 4, to = 5),
      AutoMigration(from = 5, to = 6),
      AutoMigration(from = 6, to = 7, spec = AppDatabase.DropStravaActivities::class),
      AutoMigration(from = 7, to = 8),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun trainingPlanDao(): TrainingPlanDao
  abstract fun workoutSessionDao(): WorkoutSessionDao
  abstract fun sessionEventDao(): SessionEventDao
  abstract fun ouraDao(): OuraDao
  abstract fun intervalsDao(): IntervalsDao

  /**
   * Declares that `strava_activities` is meant to disappear at version 7.
   *
   * Without this Room refuses to generate the migration at all, because a table present in one
   * schema and absent from the next is ambiguous: dropped, or renamed and its rows meant to
   * survive? Saying so here is the difference between a deliberate removal and a silent data loss.
   */
  @DeleteTable(tableName = "strava_activities")
  class DropStravaActivities : AutoMigrationSpec

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

    /**
     * There is deliberately no `fallbackToDestructiveMigration` here.
     *
     * With it, a schema change that has no migration wipes the database without a word: no error,
     * no log line, just an empty training history on the next launch. Without it, Room throws on
     * open and the app fails to start — loud, immediate, and the data is still on disk waiting for
     * the migration to be written. For a single-user app the loud failure is strictly the better
     * one; the quiet one is only discovered once the history is already gone.
     *
     * Adding a version therefore means, every time:
     *  1. change the entities and bump [version];
     *  2. for purely additive changes (new table, new nullable column, new column with a default,
     *     new index) declare `autoMigrations = [AutoMigration(from = N, to = N+1)]` on `@Database`
     *     — Room writes the SQL by diffing the exported schemas under `app/schemas/`;
     *  3. for a rename or a drop, add `@RenameColumn`/`@DeleteColumn` specs, or hand-write a
     *     [Migration] the way [MIGRATION_3_4] does, and pass it to `addMigrations`;
     *  4. add a case to `MigrationTest` — a migration nobody ran is not a migration.
     *
     * `tools/backup-db.ps1` copies the database off the device first, which is worth doing before
     * installing any build that bumps the version.
     */
    private fun build(context: Context): AppDatabase = builder(context, DB_NAME).build()

    /**
     * The one place the database is configured. `MigrationGuardTest` builds through here under a
     * throwaway file name, so what it asserts about is the real configuration rather than a copy
     * of it that could drift.
     */
    @VisibleForTesting
    internal fun builder(context: Context, name: String): RoomDatabase.Builder<AppDatabase> =
      Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(MIGRATION_3_4)
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
  }
}
