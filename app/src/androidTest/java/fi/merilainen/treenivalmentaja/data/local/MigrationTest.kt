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

  /**
   * Version 7 is the first migration that **removes** something: Strava paywalled its API, so
   * `strava_activities` goes and `intervals_activities` arrives in its place.
   *
   * Two things worth proving, and the first is the one that matters. Everything else stored — the
   * training plan, its sessions, the Oura rows — has to survive untouched, because a migration
   * that drops a table is exactly where a mistake would take neighbouring data with it. The second
   * is that the new table is really there and writable, with a **string** primary key where the old
   * one was numeric.
   *
   * The Strava row inserted below never existed on a real device — Strava was never connected to an
   * account — but it is inserted anyway, so this test proves the drop works on a populated table
   * rather than on an empty one that would hide a failure.
   */
  @Test
  fun migrate6To7() {
    var db = helper.createDatabase(TEST_DB, 6)

    db.execSQL(
      """
      INSERT INTO `training_plans` (`id`, `name`, `schemaVersion`, `timeZone`, `startDate`, `description`, `createdAt`, `contentHash`, `isActive`)
      VALUES ('plan1', 'Plan 1', 1, 'Europe/Helsinki', '2026-08-01', 'Desc', 1000, 'hash', 1)
      """
    )
    db.execSQL(
      """
      INSERT INTO `oura_daily_summaries` (`date`, `readinessScore`, `sleepScore`, `activityScore`, `fetchedAtUtc`)
      VALUES ('2026-08-15', 94, 88, 71, 1754755200000)
      """
    )
    db.execSQL(
      """
      INSERT INTO `strava_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `elapsedTimeSec`, `distanceMeters`, `avgHeartRate`, `maxHeartRate`, `elevationGainMeters`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES (12345, 'Aamulenkki', 'Run', 1754755200000, 2280, 2400, 6200.0, 148, 171, 42.0, NULL, 1754755200000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 7, true)

    // The neighbours: untouched by a migration that dropped the table beside them.
    val plans = db.query("SELECT * FROM training_plans")
    assertTrue(plans.moveToFirst())
    assertEquals("Plan 1", plans.getString(plans.getColumnIndex("name")))
    plans.close()

    val summaries = db.query("SELECT * FROM oura_daily_summaries")
    assertTrue(summaries.moveToFirst())
    assertEquals(94, summaries.getInt(summaries.getColumnIndex("readinessScore")))
    summaries.close()

    // The new table exists and takes a string id, which is what makes the sync idempotent against
    // intervals.icu's own activity identifiers.
    db.execSQL(
      """
      INSERT INTO `intervals_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `elapsedTimeSec`, `distanceMeters`, `avgHeartRate`, `maxHeartRate`, `elevationGainMeters`, `calories`, `trainingLoad`, `source`, `deviceName`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES ('i84461234', 'Aamulenkki', 'Run', 1754755200000, 2280, 2400, 6200.0, 148, 171, 42.0, 540, 78, 'SUUNTO', 'Suunto Race', NULL, 1754755200000)
      """
    )
    val activities = db.query("SELECT * FROM intervals_activities")
    assertTrue(activities.moveToFirst())
    assertEquals("i84461234", activities.getString(activities.getColumnIndex("id")))
    assertEquals("SUUNTO", activities.getString(activities.getColumnIndex("source")))
    assertEquals(78, activities.getInt(activities.getColumnIndex("trainingLoad")))
    activities.close()

    // And the old one is really gone rather than merely unused.
    val tables =
      db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='strava_activities'")
    assertEquals(0, tables.count)
    tables.close()
  }

  /**
   * Version 8 adds cadence and intensity to `intervals_activities`.
   *
   * Additive, so the thing worth proving is the usual one: an activity stored before the columns
   * existed keeps every value it had and gets nulls for the new ones — not a zero cadence, which
   * would read as a runner who never took a step.
   */
  @Test
  fun migrate7To8() {
    var db = helper.createDatabase(TEST_DB, 7)

    db.execSQL(
      """
      INSERT INTO `intervals_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `elapsedTimeSec`, `distanceMeters`, `avgHeartRate`, `maxHeartRate`, `elevationGainMeters`, `calories`, `trainingLoad`, `source`, `deviceName`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES ('i84461234', 'Aamulenkki', 'Run', 1754755200000, 2280, 2400, 6200.0, 148, 171, 42.0, 540, 78, 'SUUNTO', 'Suunto Race', 'session1', 1754755200000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 8, true)

    val activities = db.query("SELECT * FROM intervals_activities")
    assertTrue(activities.moveToFirst())
    assertEquals("i84461234", activities.getString(activities.getColumnIndex("id")))
    assertEquals(148, activities.getInt(activities.getColumnIndex("avgHeartRate")))
    assertEquals(78, activities.getInt(activities.getColumnIndex("trainingLoad")))
    assertEquals("session1", activities.getString(activities.getColumnIndex("matchedSessionId")))
    // The point of the new columns: an activity that predates them has none.
    assertTrue(activities.isNull(activities.getColumnIndex("avgCadence")))
    assertTrue(activities.isNull(activities.getColumnIndex("intensity")))
    activities.close()
  }

  /**
   * Version 9 adds the speeds, the recording time and the two extra load figures.
   *
   * `avgSpeedMps` is the one that matters: it is what the watch's own duration is recovered from,
   * so an activity synced before this version cannot show that duration until it is fetched again.
   * The test proves it gets a null rather than a zero, which would read as a runner who never
   * moved.
   */
  @Test
  fun migrate8To9() {
    var db = helper.createDatabase(TEST_DB, 8)

    db.execSQL(
      """
      INSERT INTO `intervals_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `elapsedTimeSec`, `distanceMeters`, `avgHeartRate`, `maxHeartRate`, `avgCadence`, `elevationGainMeters`, `calories`, `trainingLoad`, `intensity`, `source`, `deviceName`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES ('i176132319', 'Afternoon Run', 'Run', 1786889278000, 3226, 3752, 9520.0, 148, 174, 81, 77.0, 842, 62, 77.13892, 'SUUNTO', 'SUUNTO Suunto 5', 'session1', 1786889278000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 9, true)

    val activities = db.query("SELECT * FROM intervals_activities")
    assertTrue(activities.moveToFirst())
    assertEquals("i176132319", activities.getString(activities.getColumnIndex("id")))
    assertEquals(3226L, activities.getLong(activities.getColumnIndex("movingTimeSec")))
    assertEquals(81, activities.getInt(activities.getColumnIndex("avgCadence")))
    assertEquals("session1", activities.getString(activities.getColumnIndex("matchedSessionId")))
    assertTrue(activities.isNull(activities.getColumnIndex("avgSpeedMps")))
    assertTrue(activities.isNull(activities.getColumnIndex("maxSpeedMps")))
    assertTrue(activities.isNull(activities.getColumnIndex("recordingTimeSec")))
    assertTrue(activities.isNull(activities.getColumnIndex("hrLoad")))
    assertTrue(activities.isNull(activities.getColumnIndex("trimp")))
    activities.close()
  }

  /**
   * Version 10 adds acute and chronic load.
   *
   * Additive, and the assertion is the familiar one: an activity stored before them gets nulls
   * rather than zeros. A zero chronic load would read as an athlete with no fitness at all, which
   * is a different and much more alarming claim than "not known".
   */
  @Test
  fun migrate9To10() {
    var db = helper.createDatabase(TEST_DB, 9)

    db.execSQL(
      """
      INSERT INTO `intervals_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `recordingTimeSec`, `distanceMeters`, `avgSpeedMps`, `maxSpeedMps`, `avgHeartRate`, `maxHeartRate`, `avgCadence`, `elevationGainMeters`, `calories`, `trainingLoad`, `intensity`, `hrLoad`, `trimp`, `source`, `deviceName`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES ('i176132319', 'Afternoon Run', 'Run', 1786889278000, 3226, 3751, 9520.0, 3.096, 3.71, 148, 174, 81, 77.0, 842, 62, 77.13892, 62, 92.35979, 'SUUNTO', 'SUUNTO Suunto 5', 'session1', 1786889278000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 10, true)

    val activities = db.query("SELECT * FROM intervals_activities")
    assertTrue(activities.moveToFirst())
    assertEquals("i176132319", activities.getString(activities.getColumnIndex("id")))
    assertEquals(3.096, activities.getDouble(activities.getColumnIndex("avgSpeedMps")), 0.0001)
    assertEquals(62, activities.getInt(activities.getColumnIndex("hrLoad")))
    assertTrue(activities.isNull(activities.getColumnIndex("atl")))
    assertTrue(activities.isNull(activities.getColumnIndex("ctl")))
    activities.close()
  }

  /**
   * Version 11 adds the night's own measurements to the daily summaries.
   *
   * The first migration in a while to touch an Oura table rather than an intervals.icu one, and the
   * assertion is the same as every additive one before it — a day synced before v11 keeps its
   * scores and gets nulls, not zeros.
   *
   * That distinction matters more here than usual. A stored HRV of 0 ms is not merely wrong, it is
   * the single most alarming reading the analysis could be handed: it would be read as an athlete
   * in complete autonomic collapse and advised accordingly. "Not known" and "catastrophic" must
   * never be the same value, which is the rule the whole Oura layer is built on.
   */
  @Test
  fun migrate10To11() {
    var db = helper.createDatabase(TEST_DB, 10)

    db.execSQL(
      """
      INSERT INTO `oura_daily_summaries` (`date`, `readinessScore`, `sleepScore`, `activityScore`, `fetchedAtUtc`)
      VALUES ('2026-08-17', 72, 80, 65, 1786889278000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 11, true)

    val summaries = db.query("SELECT * FROM oura_daily_summaries")
    assertTrue(summaries.moveToFirst())
    assertEquals("2026-08-17", summaries.getString(summaries.getColumnIndex("date")))
    // The scores that were there survive.
    assertEquals(72, summaries.getInt(summaries.getColumnIndex("readinessScore")))
    assertEquals(80, summaries.getInt(summaries.getColumnIndex("sleepScore")))
    assertEquals(65, summaries.getInt(summaries.getColumnIndex("activityScore")))
    // The night's measurements are absent, not zero.
    assertTrue(summaries.isNull(summaries.getColumnIndex("averageHrvMs")))
    assertTrue(summaries.isNull(summaries.getColumnIndex("restingHrBpm")))
    assertTrue(summaries.isNull(summaries.getColumnIndex("sleepHrBpm")))
    summaries.close()
  }

  /**
   * Version 12 adds `intervals_wellness` — the daily training-load series.
   *
   * A new table rather than new columns, so the assertion is different in kind from the additive
   * ones before it: the existing rows must be untouched, and the new table must exist and be empty.
   * Empty is correct — the ordinary sync fills it on the next resume, and inventing rows during a
   * migration would be inventing training history.
   *
   * It exists because `intervals_activities.atl`/`ctl` were the wrong numbers to read for "how
   * loaded is the athlete now": they are frozen at the moment of a session and never decay. Those
   * columns are deliberately still here, which this test also pins — the load immediately after a
   * session is a true and different fact, and dropping it would lose data to fix a misuse.
   */
  @Test
  fun migrate11To12() {
    var db = helper.createDatabase(TEST_DB, 11)

    db.execSQL(
      """
      INSERT INTO `intervals_activities` (`id`, `name`, `sportType`, `startTimeUtc`, `movingTimeSec`, `recordingTimeSec`, `distanceMeters`, `avgSpeedMps`, `maxSpeedMps`, `avgHeartRate`, `maxHeartRate`, `avgCadence`, `elevationGainMeters`, `calories`, `trainingLoad`, `intensity`, `hrLoad`, `trimp`, `atl`, `ctl`, `source`, `deviceName`, `matchedSessionId`, `fetchedAtUtc`)
      VALUES ('i176422661', 'Afternoon Run', 'Run', 1786889278000, 2117, 2117, 6029.0, 2.892, 3.62, 149, 167, 79, 68.4, 481, 35, 77.14803, 35, 60.31146, 17.650415, 11.711897, 'SUUNTO', 'SUUNTO Suunto 5', NULL, 1786889278000)
      """
    )
    db.execSQL(
      """
      INSERT INTO `oura_daily_summaries` (`date`, `readinessScore`, `sleepScore`, `activityScore`, `averageHrvMs`, `restingHrBpm`, `sleepHrBpm`, `fetchedAtUtc`)
      VALUES ('2026-08-19', 84, 86, 92, 34, 51, 55, 1786889278000)
      """
    )
    db.close()

    db = helper.runMigrationsAndValidate(TEST_DB, 12, true)

    // The new table is there and empty; the next sync fills it.
    val wellness = db.query("SELECT * FROM intervals_wellness")
    assertEquals(0, wellness.count)
    wellness.close()

    // The per-activity load figures survive. They are a record of the load right after that
    // session, which is a legitimate fact — just not the one the analysis should read.
    val activities = db.query("SELECT * FROM intervals_activities")
    assertTrue(activities.moveToFirst())
    assertEquals(17.650415, activities.getDouble(activities.getColumnIndex("atl")), 0.0001)
    assertEquals(11.711897, activities.getDouble(activities.getColumnIndex("ctl")), 0.0001)
    activities.close()

    // And v11's own columns are undisturbed by a table being added beside them.
    val summaries = db.query("SELECT * FROM oura_daily_summaries")
    assertTrue(summaries.moveToFirst())
    assertEquals(34, summaries.getInt(summaries.getColumnIndex("averageHrvMs")))
    assertEquals(51, summaries.getInt(summaries.getColumnIndex("restingHrBpm")))
    summaries.close()
  }
}
