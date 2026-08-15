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
      AppDatabase::class.java,
      emptyList(),
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

  /**
   * Version 5 adds distance and the two heart-rate columns to `oura_workouts`.
   *
   * An auto migration, so what is being tested is really Room's diff of the exported schemas — and
   * that is worth running rather than trusting, because the failure mode of a wrong one is an app
   * that will not open. The row inserted first is what proves it: an existing workout must survive
   * with its old values intact and the new columns null, not be recreated empty.
   */
  @Test
  fun migrate4To5() {
    var db = helper.createDatabase(TEST_DB, 4)

    db.execSQL(
      """
      INSERT INTO `oura_workouts` (`id`, `activityType`, `startTimeUtc`, `endTimeUtc`, `calories`, `matchedSessionId`)
      VALUES ('w1', 'running', 1754755200000, 1754757600000, 431.0, 'session1')
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 5, true)

    val cursor = db.query("SELECT * FROM oura_workouts")
    assertTrue(cursor.moveToFirst())
    assertEquals("w1", cursor.getString(cursor.getColumnIndex("id")))
    assertEquals("running", cursor.getString(cursor.getColumnIndex("activityType")))
    assertEquals(1754755200000L, cursor.getLong(cursor.getColumnIndex("startTimeUtc")))
    assertEquals("session1", cursor.getString(cursor.getColumnIndex("matchedSessionId")))
    // The point of the new columns: a workout that existed before them has none, and that has to
    // read as "not known" rather than as a zero heart rate.
    assertTrue(cursor.isNull(cursor.getColumnIndex("distanceMeters")))
    assertTrue(cursor.isNull(cursor.getColumnIndex("avgHeartRate")))
    assertTrue(cursor.isNull(cursor.getColumnIndex("maxHeartRate")))
    cursor.close()
  }

  /**
   * Version 6 adds `strava_activities`. A new table rather than new columns, so the thing worth
   * proving is the other half: everything already stored survives untouched, and the new table
   * exists and is writable afterwards.
   */
  @Test
  fun migrate5To6() {
    var db = helper.createDatabase(TEST_DB, 5)

    db.execSQL(
      """
      INSERT INTO `oura_workouts` (`id`, `activityType`, `startTimeUtc`, `endTimeUtc`, `calories`, `matchedSessionId`, `distanceMeters`, `avgHeartRate`, `maxHeartRate`)
      VALUES ('w1', 'running', 1754755200000, 1754757600000, 431.0, 'session1', 6200.0, 142, 168)
      """
    )
    db.execSQL(
      """
      INSERT INTO `oura_daily_summaries` (`date`, `readinessScore`, `sleepScore`, `activityScore`, `fetchedAtUtc`)
      VALUES ('2026-08-15', 94, 88, 71, 1754755200000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 6, true)

    // The existing rows are the point: a new table must not disturb them.
    val workouts = db.query("SELECT * FROM oura_workouts")
    assertTrue(workouts.moveToFirst())
    assertEquals("w1", workouts.getString(workouts.getColumnIndex("id")))
    assertEquals(142, workouts.getInt(workouts.getColumnIndex("avgHeartRate")))
    workouts.close()

    val summaries = db.query("SELECT * FROM oura_daily_summaries")
    assertTrue(summaries.moveToFirst())
    assertEquals(94, summaries.getInt(summaries.getColumnIndex("readinessScore")))
    summaries.close()

    // And the new table is really there, not merely declared.
    db.execSQL(
      """
      INSERT INTO `strava_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `elapsedTimeSec`, `distanceMeters`, `avgHeartRate`, `maxHeartRate`, `elevationGainMeters`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES (12345, 'Aamulenkki', 'Run', 1754755200000, 2280, 2400, 6200.0, 148, 171, 42.0, NULL, 1754755200000)
      """
    )
    val activities = db.query("SELECT * FROM strava_activities")
    assertTrue(activities.moveToFirst())
    assertEquals(12345L, activities.getLong(activities.getColumnIndex("id")))
    assertEquals("Run", activities.getString(activities.getColumnIndex("sportType")))
    assertEquals(2280L, activities.getLong(activities.getColumnIndex("movingTimeSec")))
    assertTrue(activities.isNull(activities.getColumnIndex("matchedSessionId")))
    activities.close()
  }
}
