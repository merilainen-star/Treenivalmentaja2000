package fi.merilainen.treenivalmentaja.data.strava

import fi.merilainen.treenivalmentaja.domain.StravaRunMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strava's summaries as rows, and the pace derived from them.
 *
 * The rule under test throughout is the one the Oura mappers obey: **missing is not zero**. A run
 * with no heart-rate sensor is a row with no heart rate, never a row claiming 0 bpm.
 */
class StravaMappersTest {

  @Test
  fun `a complete activity becomes a row`() {
    val rows =
      StravaMappers.toActivities(
        listOf(
          StravaActivityDto(
            id = 12345,
            name = "Aamulenkki",
            sportType = "Run",
            startDate = "2026-08-15T06:12:03Z",
            movingTime = 2280,
            elapsedTime = 2400,
            distance = 6200.0,
            averageSpeed = 2.72,
            averageHeartrate = 148.4,
            maxHeartrate = 171.0,
            totalElevationGain = 42.0,
          )
        ),
        fetchedAtUtc = 1_755_000_000_000,
      )

    assertEquals(1, rows.size)
    val row = rows.single()
    assertEquals(12345L, row.id)
    assertEquals("Run", row.sportType)
    assertEquals(2280L, row.movingTimeSec)
    assertEquals(6200.0, row.distanceMeters!!, 0.001)
    // Strava reports heart rate as a double; the row keeps whole beats.
    assertEquals(148, row.avgHeartRate)
    assertEquals(171, row.maxHeartRate)
    // Never decided by the parser — matching is a training question.
    assertNull(row.matchedSessionId)
  }

  @Test
  fun `an activity with no sensors keeps nulls rather than zeros`() {
    val rows =
      StravaMappers.toActivities(
        listOf(
          StravaActivityDto(
            id = 1,
            sportType = "Run",
            startDate = "2026-08-15T06:00:00Z",
            movingTime = 1800,
            distance = 5000.0,
            averageHeartrate = null,
            maxHeartrate = null,
            totalElevationGain = 0.0,
          )
        ),
        fetchedAtUtc = 0,
      )

    val row = rows.single()
    assertNull(row.avgHeartRate)
    assertNull(row.maxHeartRate)
    // Zero elevation is not a measurement worth drawing either — a flat run reports 0.0 and the
    // screen would print "nousu 0 m" for it.
    assertNull(row.elevationGainMeters)
  }

  /**
   * The rows exist to be placed on the clock and reduced to a pace. One that cannot do either is
   * dropped here rather than stored as something no screen can render.
   */
  @Test
  fun `activities too incomplete to place are dropped`() {
    val rows =
      StravaMappers.toActivities(
        listOf(
          StravaActivityDto(id = null, sportType = "Run", startDate = "2026-08-15T06:00:00Z", movingTime = 60),
          StravaActivityDto(id = 2, sportType = null, startDate = "2026-08-15T06:00:00Z", movingTime = 60),
          StravaActivityDto(id = 3, sportType = "Run", startDate = null, movingTime = 60),
          StravaActivityDto(id = 4, sportType = "Run", startDate = "not a date", movingTime = 60),
          StravaActivityDto(id = 5, sportType = "Run", startDate = "2026-08-15T06:00:00Z", movingTime = 0),
        ),
        fetchedAtUtc = 0,
      )

    assertTrue(rows.toString(), rows.isEmpty())
  }

  // ------------------------------------------------------------------ pace

  /** 38 minutes over 6.2 km is 6:07 per kilometre. */
  @Test
  fun `pace comes from moving time and distance`() {
    val metrics =
      StravaRunMetrics(
        activityId = 1,
        sportType = "Run",
        startTimeUtc = 0,
        movingTimeSec = 2280,
        distanceKm = 6.2,
      )

    assertEquals(367, metrics.paceSecPerKm)
    assertEquals("6:07 /km", metrics.paceText)
  }

  /** A strength session recorded in Strava has no distance, and therefore no pace to print. */
  @Test
  fun `no distance means no pace, not a division by zero`() {
    val metrics =
      StravaRunMetrics(
        activityId = 1,
        sportType = "WeightTraining",
        startTimeUtc = 0,
        movingTimeSec = 2700,
        distanceKm = null,
      )

    assertNull(metrics.paceSecPerKm)
    assertNull(metrics.paceText)
  }

  /** A GPS blip of a few metres would otherwise produce a pace of hours per kilometre. */
  @Test
  fun `a distance too small to be a run yields no pace`() {
    val metrics =
      StravaRunMetrics(
        activityId = 1,
        sportType = "Run",
        startTimeUtc = 0,
        movingTimeSec = 600,
        distanceKm = 0.01,
      )

    assertNull(metrics.paceSecPerKm)
  }

  @Test
  fun `seconds under ten are zero-padded so the pace reads as a clock`() {
    val metrics =
      StravaRunMetrics(
        activityId = 1,
        sportType = "Run",
        startTimeUtc = 0,
        movingTimeSec = 1806,
        distanceKm = 6.0,
      )

    assertEquals("5:01 /km", metrics.paceText)
  }
}
