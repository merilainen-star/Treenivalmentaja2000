package fi.merilainen.treenivalmentaja.data.intervals

import fi.merilainen.treenivalmentaja.domain.CompletedRunMetrics
import fi.merilainen.treenivalmentaja.domain.formatDuration
import kotlin.math.roundToInt
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * intervals.icu's summaries as rows, and the pace derived from them.
 *
 * The rule under test throughout is the one the Oura mappers obey: **missing is not zero**. A run
 * recorded without a heart-rate strap is a row with no heart rate, never a row claiming 0 bpm.
 */
class IntervalsMappersTest {

  private val helsinki = ZoneId.of("Europe/Helsinki")

  @Test
  fun `a complete activity becomes a row`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "i84461234",
            name = "Aamulenkki",
            type = "Run",
            startDate = "2026-08-15T06:12:03Z",
            startDateLocal = "2026-08-15T09:12:03",
            movingTime = 2280,
            elapsedTime = 2400,
            distance = 6200.0,
            averageHeartrate = 148,
            maxHeartrate = 171,
            totalElevationGain = 42.0,
            calories = 540,
            icuTrainingLoad = 78,
            icuAtl = 14.986127,
            icuCtl = 11.150764,
            source = "SUUNTO",
            deviceName = "Suunto Race",
          )
        ),
        fetchedAtUtc = 1_755_000_000_000,
        zone = helsinki,
      )

    val row = rows.single()
    // A string id, unlike Strava's numeric one — and the reason the sync is idempotent.
    assertEquals("i84461234", row.id)
    assertEquals("Run", row.sportType)
    assertEquals(2280L, row.movingTimeSec)
    assertEquals(6200.0, row.distanceMeters!!, 0.001)
    assertEquals(148, row.avgHeartRate)
    assertEquals(540, row.calories)
    assertEquals(78, row.trainingLoad)
    assertEquals("SUUNTO", row.source)
    // Acute and chronic load. Nothing reads them yet — the fatigue rule in ROADMAP.md is what
    // will — but they are stored because that use is named rather than hypothetical.
    assertEquals(14.986127, row.atl!!, 0.000001)
    assertEquals(11.150764, row.ctl!!, 0.000001)
    // Never decided by the parser — matching is a training question.
    assertNull(row.matchedSessionId)
  }

  /** `start_date` is UTC and unambiguous, so it is preferred over the local wall clock. */
  @Test
  fun `the UTC start wins over the local one`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "a",
            type = "Run",
            startDate = "2026-08-15T06:12:03Z",
            // Deliberately inconsistent: if this were used, the instant would differ by hours.
            startDateLocal = "2026-08-15T20:00:00",
            movingTime = 60,
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    assertEquals(1_786_774_323_000L, rows.single().startTimeUtc)
  }

  /**
   * With no UTC field the local one is read against the device's zone — not as if it were UTC.
   * Getting that backwards moves an evening session by hours and makes the matcher pair a run with
   * the wrong session.
   */
  @Test
  fun `a local-only start is read in the device zone`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "a",
            type = "Run",
            startDate = null,
            startDateLocal = "2026-08-15T09:12:03",
            movingTime = 60,
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    // 09:12:03 in Helsinki in August is UTC+3, so 06:12:03Z — the same instant as the test above.
    assertEquals(1_786_774_323_000L, rows.single().startTimeUtc)
  }

  @Test
  fun `an activity with no sensors keeps nulls rather than zeros`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "a",
            type = "Run",
            startDate = "2026-08-15T06:00:00Z",
            movingTime = 1800,
            distance = 5000.0,
            averageHeartrate = null,
            maxHeartrate = null,
            // A flat run reports zero climb; "nousu 0 m" on screen is noise, not a measurement.
            totalElevationGain = 0.0,
            calories = null,
            icuTrainingLoad = null,
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    val row = rows.single()
    assertNull(row.avgHeartRate)
    assertNull(row.maxHeartRate)
    assertNull(row.elevationGainMeters)
    assertNull(row.calories)
    assertNull(row.trainingLoad)
  }

  /**
   * These rows exist to be placed on the clock and reduced to a pace. One that can do neither is
   * dropped here rather than stored as something no screen can render.
   */
  @Test
  fun `activities too incomplete to place are dropped`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(id = null, type = "Run", startDate = "2026-08-15T06:00:00Z", movingTime = 60),
          IntervalsActivityDto(id = "b", type = null, startDate = "2026-08-15T06:00:00Z", movingTime = 60),
          IntervalsActivityDto(id = "c", type = "Run", startDate = null, startDateLocal = null, movingTime = 60),
          IntervalsActivityDto(id = "d", type = "Run", startDate = "not a date", startDateLocal = "also not", movingTime = 60),
          IntervalsActivityDto(id = "e", type = "Run", startDate = "2026-08-15T06:00:00Z", movingTime = 0),
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    assertTrue(rows.toString(), rows.isEmpty())
  }

  /**
   * A run uploaded by hand is still that run. `source` is stored because it answers "did this come
   * off the watch", but nothing filters on it — dropping a `MANUAL` activity would be the same
   * mistake as dropping an Oura workout whose activity word the app did not recognise.
   */
  @Test
  fun `a manual upload is kept, with its source recorded`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "m1",
            type = "Run",
            startDate = "2026-08-15T06:00:00Z",
            movingTime = 1800,
            source = "MANUAL",
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    assertEquals("MANUAL", rows.single().source)
  }

  // ------------------------------------------------------------------ pace

  /** 38 minutes over 6.2 km is 6:07 per kilometre. */
  @Test
  fun `pace comes from moving time and distance`() {
    val metrics = metrics(movingTimeSec = 2280, distanceKm = 6.2)

    assertEquals(368, metrics.paceSecPerKm)
    assertEquals("6:08 /km", metrics.paceText)
  }

  /** A strength session has no distance, and therefore no pace to print. */
  @Test
  fun `no distance means no pace, not a division by zero`() {
    val metrics = metrics(movingTimeSec = 2700, distanceKm = null)

    assertNull(metrics.paceSecPerKm)
    assertNull(metrics.paceText)
  }

  /** A GPS blip of a few metres would otherwise produce a pace of hours per kilometre. */
  @Test
  fun `a distance too small to be a run yields no pace`() {
    assertNull(metrics(movingTimeSec = 600, distanceKm = 0.01).paceSecPerKm)
  }

  @Test
  fun `seconds under ten are zero-padded so the pace reads as a clock`() {
    assertEquals("5:01 /km", metrics(movingTimeSec = 1806, distanceKm = 6.0).paceText)
  }

  // ------------------------------------------------------------------ distance and intensity

  /**
   * The specification describes neither `distance` nor `icu_distance` and does not say how they
   * differ, so the mapper states a preference instead of pretending to know: intervals.icu's own
   * field wins.
   */
  @Test
  fun `icu_distance is preferred over distance`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "a",
            type = "Run",
            startDate = "2026-08-15T06:00:00Z",
            movingTime = 1800,
            distance = 5000.0,
            icuDistance = 5120.0,
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    assertEquals(5120.0, rows.single().distanceMeters!!, 0.001)
  }

  /** And falls back, so an activity carrying only the plain field still has a distance. */
  @Test
  fun `distance is used when icu_distance is absent`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "a",
            type = "Run",
            startDate = "2026-08-15T06:00:00Z",
            movingTime = 1800,
            distance = 5000.0,
            icuDistance = null,
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    assertEquals(5000.0, rows.single().distanceMeters!!, 0.001)
  }

  /** Cadence is a float in the schema but reads as whole steps per minute. */
  @Test
  fun `cadence is rounded to whole steps per minute`() {
    val rows =
      IntervalsMappers.toActivities(
        listOf(
          IntervalsActivityDto(
            id = "a",
            type = "Run",
            startDate = "2026-08-15T06:00:00Z",
            movingTime = 1800,
            averageCadence = 167.6,
            icuIntensity = 0.78,
          )
        ),
        fetchedAtUtc = 0,
        zone = helsinki,
      )

    assertEquals(168, rows.single().avgCadence)
    // Intensity is stored **raw**: normalising on the way in would bake a guess into the database.
    assertEquals(0.78, rows.single().intensity!!, 0.0001)
  }

  /**
   * The API's scale for intensity is undocumented, so the reading is normalised at display time
   * and bounded so that no real value is ambiguous — a fraction above 3.0 and an intensity above
   * 300 % are both impossible for a training session.
   */
  @Test
  fun `intensity reads as a percentage whichever scale the service used`() {
    assertEquals(78, metrics(1800, 5.0).copy(intensity = 0.78).intensityPercent)
    assertEquals(78, metrics(1800, 5.0).copy(intensity = 78.0).intensityPercent)
  }

  @Test
  fun `an absent intensity stays absent rather than becoming zero percent`() {
    assertNull(metrics(1800, 5.0).copy(intensity = null).intensityPercent)
  }

  // ------------------------------------------------------------------ the real run

  /**
   * A real Suunto run, captured from the raw-data screen on 2026-08-15 and used here as the
   * fixture the whole duration design was settled against.
   *
   * The watch said 9.52 km, 51:14.8 active, 11:16.5 paused, 1:02:31 total, 5:22 /km.
   * intervals.icu said 53:46 moving and 5:39 /km. Both are right about different things, and
   * these assertions are what keep the app from quietly picking one and forgetting the others.
   */
  private val realRun =
    CompletedRunMetrics(
      activityId = "i176132319",
      sportType = "Run",
      startTimeUtc = 1_786_889_278_000L,
      movingTimeSec = 3226,
      recordingTimeSec = 3751,
      distanceKm = 9.52,
      avgSpeedMps = 3.096,
      maxSpeedMps = 3.71,
      avgHeartRate = 148,
      maxHeartRate = 174,
      avgCadence = 81,
      elevationGainMeters = 77,
      calories = 842,
      trainingLoad = 62,
      intensity = 77.13892,
      hrLoad = 62,
      trimp = 92.35979,
      deviceName = "SUUNTO Suunto 5",
    )

  /** 9520 m / 3.096 m/s = 3074.9 s, against a watch that reported 51:14.8. */
  @Test
  fun `the watch's own duration is recovered from distance and average speed`() {
    assertEquals(3075L, realRun.activeDurationSec)
    assertEquals("51:15", realRun.activeDurationSec!!.formatDuration())
  }

  /** All three durations survive, because all three are true of the same run. */
  @Test
  fun `moving time and recording time are kept beside it`() {
    assertEquals("53:46", realRun.movingTimeSec.formatDuration())
    assertEquals("1:02:31", realRun.recordingTimeSec!!.formatDuration())
  }

  /** The watch's pace leads, because it is the number the runner saw on their wrist. */
  @Test
  fun `pace is the watch's, not intervals icu's`() {
    assertEquals("5:23 /km", realRun.paceText)
    // What the app used to show, from moving time — kept as an assertion so the difference stays
    // visible if anyone changes which duration leads.
    assertEquals(339, (3226.0 / 9.52).roundToInt())
  }

  @Test
  fun `max speed becomes the fastest pace`() {
    // 1000 / 3.71 m/s = 269.5 s/km, rounded.
    assertEquals("4:30 /km", realRun.maxPaceText)
  }

  /** 81 cycles per leg is 162 steps, which is the figure a runner recognises. */
  @Test
  fun `cadence is doubled into steps per minute`() {
    assertEquals(162, realRun.stepsPerMinute)
  }

  /** Real data settled the scale: 77.13892 is already a percentage. */
  @Test
  fun `the real intensity reads as a whole percentage`() {
    assertEquals(77, realRun.intensityPercent)
  }

  /** Without a speed there is no watch duration, and the moving time leads instead. */
  @Test
  fun `a run with no average speed falls back to moving time`() {
    val noSpeed = realRun.copy(avgSpeedMps = null)

    assertNull(noSpeed.activeDurationSec)
    assertEquals(3226L, noSpeed.primaryDurationSec)
    assertEquals("5:39 /km", noSpeed.paceText)
  }

  /** Under an hour reads as a stopwatch does, without a leading zero hour. */
  @Test
  fun `durations show hours only when there are hours`() {
    assertEquals("51:15", 3075L.formatDuration())
    assertEquals("1:02:31", 3751L.formatDuration())
    assertEquals("0:45", 45L.formatDuration())
  }

  /**
   * The rounding fix. 3075 s over 9.52 km is 323.0 s/km; truncation and rounding agree here, so
   * the case that proves it is the one that used to be wrong: 3226 s over 9.52 km is 338.87, and
   * the old code printed 5:38 where both the watch and intervals.icu said 5:39.
   */
  @Test
  fun `pace is rounded rather than truncated`() {
    val movingOnly = realRun.copy(avgSpeedMps = null)

    assertEquals(339, movingOnly.paceSecPerKm)
    assertEquals("5:39 /km", movingOnly.paceText)
  }

  private fun metrics(movingTimeSec: Long, distanceKm: Double?) =
    CompletedRunMetrics(
      activityId = "i1",
      sportType = "Run",
      startTimeUtc = 0,
      movingTimeSec = movingTimeSec,
      distanceKm = distanceKm,
    )
}
