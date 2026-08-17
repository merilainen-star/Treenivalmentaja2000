package fi.merilainen.treenivalmentaja.data.oura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which sleep period becomes the day's nightly measurements.
 *
 * Its own suite rather than a few cases bolted onto `OuraMappersTest`, because this is the one
 * collection that returns **several documents per day** and picking the wrong one is silent: a
 * twenty-minute nap's HRV is a perfectly plausible number, just not the one the trend is made of.
 */
class OuraSleepPeriodMappingTest {

  @Test
  fun `takes the long sleep rather than the nap`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods =
          listOf(
            period(type = "late_nap", hrv = 90, lowestHr = 60, seconds = 1_200),
            period(type = "long_sleep", hrv = 61, lowestHr = 48, seconds = 27_000),
          ),
      )

    assertEquals(1, rows.size)
    assertEquals(61, rows.single().averageHrvMs)
    assertEquals(48, rows.single().restingHrBpm)
  }

  /**
   * The nap's numbers must not be blended into the night's. Averaging 90 and 61 would give 75 —
   * a number that describes neither, and one nobody could ever trace back to a measurement.
   */
  @Test
  fun `does not average the periods together`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods =
          listOf(
            period(type = "long_sleep", hrv = 61, lowestHr = 48, seconds = 27_000),
            period(type = "late_nap", hrv = 90, lowestHr = 60, seconds = 1_200),
          ),
      )

    assertEquals(61, rows.single().averageHrvMs)
  }

  /** A period the user rejected as not-sleep is not a measurement of their night. */
  @Test
  fun `ignores rejected and deleted periods`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods =
          listOf(
            period(type = "rest", hrv = 90, lowestHr = 70, seconds = 3_000),
            period(type = "deleted", hrv = 95, lowestHr = 72, seconds = 3_000),
            period(type = "long_sleep", hrv = 61, lowestHr = 48, seconds = 27_000),
          ),
      )

    assertEquals(61, rows.single().averageHrvMs)
  }

  /** A day holding only rejected periods has no night at all — not a night of zeroes. */
  @Test
  fun `yields no measurements when every period was rejected`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = listOf(OuraDailyScoreDto(day = DAY, score = 70)),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods = listOf(period(type = "rest", hrv = 90, lowestHr = 70, seconds = 3_000)),
      )

    assertNull(rows.single().averageHrvMs)
    assertNull(rows.single().restingHrBpm)
    // The readiness score still arrived, so the row is not empty — only the night is missing.
    assertEquals(70, rows.single().readinessScore)
  }

  /**
   * A short night is still that night's only measurement. Reporting nothing would be worse than
   * reporting a three-hour one.
   */
  @Test
  fun `falls back to the longest period when there is no long sleep`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods =
          listOf(
            period(type = "sleep", hrv = 70, lowestHr = 55, seconds = 4_000),
            period(type = "sleep", hrv = 58, lowestHr = 50, seconds = 9_000),
          ),
      )

    assertEquals(58, rows.single().averageHrvMs)
  }

  /** Missing is not zero: a night the ring reported no HRV for keeps a null, not a 0. */
  @Test
  fun `stores a null for a measurement the ring did not report`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods = listOf(period(type = "long_sleep", hrv = null, lowestHr = 48, seconds = 27_000)),
      )

    assertNull(rows.single().averageHrvMs)
    assertEquals(48, rows.single().restingHrBpm)
  }

  /** The day is the row's primary key; a period without one cannot be addressed. */
  @Test
  fun `drops periods with no day`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods =
          listOf(period(type = "long_sleep", hrv = 61, lowestHr = 48, seconds = 27_000, day = null)),
      )

    assertEquals(0, rows.size)
  }

  /**
   * The night merges onto the row the scores already made, rather than creating a second one — the
   * `day` field is the morning you wake up, which is exactly how the summaries are keyed.
   */
  @Test
  fun `merges onto the same row as the day's scores`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = listOf(OuraDailyScoreDto(day = DAY, score = 72)),
        sleep = listOf(OuraDailyScoreDto(day = DAY, score = 80)),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods = listOf(period(type = "long_sleep", hrv = 61, lowestHr = 48, seconds = 27_000)),
      )

    assertEquals(1, rows.size)
    assertEquals(72, rows.single().readinessScore)
    assertEquals(80, rows.single().sleepScore)
    assertEquals(61, rows.single().averageHrvMs)
  }

  /** Sleep heart rate arrives as a decimal and is stored rounded, not truncated. */
  @Test
  fun `rounds the average sleep heart rate`() {
    val rows =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
        sleepPeriods =
          listOf(
            period(type = "long_sleep", hrv = 61, lowestHr = 48, seconds = 27_000, avgHr = 52.6)
          ),
      )

    assertEquals(53, rows.single().sleepHrBpm)
  }

  private fun period(
    type: String?,
    hrv: Int?,
    lowestHr: Int?,
    seconds: Int?,
    avgHr: Double? = 52.0,
    day: String? = DAY,
  ) =
    OuraSleepPeriodDto(
      id = "sleep-$type-$seconds",
      day = day,
      type = type,
      averageHrv = hrv,
      lowestHeartRate = lowestHr,
      averageHeartRate = avgHr,
      totalSleepDuration = seconds,
    )

  private companion object {
    const val DAY = "2026-08-17"
    const val FETCHED_AT = 1_754_800_000_000L
  }
}
