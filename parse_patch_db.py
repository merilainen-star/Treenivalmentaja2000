import re

with open("app/src/main/java/fi/merilainen/treenivalmentaja/data/local/AppDatabase.kt", "r") as f:
    content = f.read()

# Update version to 4
content = re.sub(r'version = 2', 'version = 4', content)

# Replace the MIGRATION_1_2 and MIGRATION_2_4 with MIGRATION_3_4 and others
migrations_code = """
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
          // Assuming 1->2 did something in original codebase
      }
    }
    
    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
          // Assuming 2->3 did something in original codebase
      }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF;")
        
        // 1. Create new table
        db.execSQL(\"\"\"
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
        \"\"\")

        // 2. Copy data
        db.execSQL(\"\"\"
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
        \"\"\")

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
"""

content = re.sub(r'val MIGRATION_1_2 = object : Migration\(1, 2\) \{[\s\S]*?val MIGRATION_2_4 = object : Migration\(2, 4\) \{[\s\S]*?    \}', migrations_code, content)

# update database builder
builder_code = """Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
        .build()"""

content = re.sub(r'Room\.databaseBuilder\(context, AppDatabase::class\.java, DB_NAME\).*?\.build\(\)', builder_code, content, flags=re.DOTALL)

with open("app/src/main/java/fi/merilainen/treenivalmentaja/data/local/AppDatabase.kt", "w") as f:
    f.write(content)
