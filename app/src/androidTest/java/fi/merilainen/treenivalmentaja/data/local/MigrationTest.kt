package fi.merilainen.treenivalmentaja.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

private const val TEST_DB = "migration-test"

@RunWith(AndroidJUnit4::class)
class MigrationTest {
  @get:Rule
  val helper: MigrationTestHelper =
    MigrationTestHelper(
      InstrumentationRegistry.getInstrumentation(),
      AppDatabase::class.java.canonicalName,
      FrameworkSQLiteOpenHelperFactory()
    )

  @Test
  fun migrate3To4() {
    var db = helper.createDatabase(TEST_DB, 3)

    db.execSQL(
      """
      INSERT INTO `training_plans` (`id`, `name`, `schemaVersion`, `timeZone`, `startDate`, `description`, `createdAt`, `contentHash`, `isActive`) 
      VALUES ('plan1', 'Plan 1', 1, 'Europe/Helsinki', '2026-08-01', 'Desc', 1000, 'hash', 1)
      """
    )
    
    db.execSQL(
      """
      INSERT INTO `workout_sessions` (`id`, `planId`, `type`, `weekNumber`, `scheduledDate`, `scheduledTime`, `scheduledAtUtc`, `durationMin`, `distanceKm`, `intensity`, `rounds`, `roundsMin`, `roundsMax`, `targetPace`, `warmupSec`, `exercisesJson`, `lighterAlternativeJson`, `description`, `status`, `appliedLighterVariant`, `originalSessionId`, `updatedAt`)
      VALUES ('session1', 'plan1', 'RUN', 1, '2026-08-01', '18:00', 1690902000000, 60, 5.0, 'EASY', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'Run', 'PLANNED', 0, NULL, 1000)
      """
    )

    db.execSQL(
      """
      INSERT INTO `workout_sessions` (`id`, `planId`, `type`, `weekNumber`, `scheduledDate`, `scheduledTime`, `scheduledAtUtc`, `durationMin`, `distanceKm`, `intensity`, `rounds`, `roundsMin`, `roundsMax`, `targetPace`, `warmupSec`, `exercisesJson`, `lighterAlternativeJson`, `description`, `status`, `appliedLighterVariant`, `originalSessionId`, `updatedAt`)
      VALUES ('session2', 'plan1', 'GYM', 1, '2026-08-02', '17:00', 1690984800000, 45, NULL, 'HARD', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'Gym', 'COMPLETED', 0, NULL, 1000)
      """
    )
    
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

    val cursor = db.query("SELECT * FROM workout_sessions ORDER BY id ASC")
    
    assertTrue(cursor.moveToFirst())
    assertEquals("session1", cursor.getString(cursor.getColumnIndex("id")))
    assertEquals(1690902000000L, cursor.getLong(cursor.getColumnIndex("remindAtUtc")))
    assertEquals(0, cursor.getInt(cursor.getColumnIndex("timeIsFixed")))
    assertTrue(cursor.isNull(cursor.getColumnIndex("reminderOverride")))

    assertTrue(cursor.moveToNext())
    assertEquals("session2", cursor.getString(cursor.getColumnIndex("id")))
    assertEquals(1690984800000L, cursor.getLong(cursor.getColumnIndex("remindAtUtc")))
    assertEquals(0, cursor.getInt(cursor.getColumnIndex("timeIsFixed")))
    assertTrue(cursor.isNull(cursor.getColumnIndex("reminderOverride")))
    
    // Check total row count
    val countCursor = db.query("SELECT COUNT(*) FROM workout_sessions")
    assertTrue(countCursor.moveToFirst())
    assertEquals(2, countCursor.getInt(0))
    countCursor.close()

    // Check index
    val indexCursor = db.query("PRAGMA index_list('workout_sessions')")
    var hasIndex = false
    while (indexCursor.moveToNext()) {
      if (indexCursor.getString(indexCursor.getColumnIndex("name")) == "index_workout_sessions_remindAtUtc") {
        hasIndex = true
        break
      }
    }
    assertTrue("Index index_workout_sessions_remindAtUtc should exist", hasIndex)
    indexCursor.close()
    
    cursor.close()
  }
}
