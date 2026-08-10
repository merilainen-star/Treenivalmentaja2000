package fi.merilainen.treenivalmentaja.data.oura

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Oura's documents as rows.
 *
 * Most of what is asserted here is about absence: which days exist, which scores are `null`, and
 * which rows are dropped rather than stored half-formed. A zero would pass a type check everywhere
 * a `null` does, and would be read as "you are exhausted" on a day the ring was simply on the
 * bedside table.
 */
class OuraMappersTest {

  // ------------------------------------------------------------------ daily summaries

  @Test
  fun `three collections become one row per day`() {
    val summaries =
      OuraMappers.toDailySummaries(
        readiness = listOf(day("2026-08-07", 66), day("2026-08-08", 71)),
        sleep = listOf(day("2026-08-07", 80), day("2026-08-08", 62)),
        activity = listOf(day("2026-08-07", 91), day("2026-08-08", 88)),
        fetchedAtUtc = FETCHED_AT,
      )

    assertEquals(2, summaries.size)
    val first = summaries.first()
    assertEquals("2026-08-07", first.date)
    assertEquals(66, first.readinessScore)
    assertEquals(80, first.sleepScore)
    assertEquals(91, first.activityScore)
    assertEquals(FETCHED_AT, first.fetchedAtUtc)
  }

  /**
   * The ring was worn, the day exists, and Oura had no readiness score for it. The row has to be
   * able to say that — the recovery card's whole design turns on being able to show "ei tietoa" for
   * a day that exists.
   */
  @Test
  fun `a day without a score is a row with no score, not a row with zero`() {
    val summaries =
      OuraMappers.toDailySummaries(
        readiness = listOf(day("2026-08-08", null)),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
      )

    val summary = summaries.single()
    assertEquals("2026-08-08", summary.date)
    assertNull(summary.readinessScore)
    assertNull(summary.sleepScore)
    assertNull(summary.activityScore)
  }

  /** A day only one collection mentioned is still a day. The other two columns stay empty. */
  @Test
  fun `a day present in one collection alone still gets a row`() {
    val summaries =
      OuraMappers.toDailySummaries(
        readiness = emptyList(),
        sleep = listOf(day("2026-08-09", 74)),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
      )

    val summary = summaries.single()
    assertEquals(74, summary.sleepScore)
    assertNull(summary.readinessScore)
  }

  @Test
  fun `rows are ordered by day`() {
    val summaries =
      OuraMappers.toDailySummaries(
        readiness = listOf(day("2026-08-09", 1)),
        sleep = listOf(day("2026-08-07", 2)),
        activity = listOf(day("2026-08-08", 3)),
        fetchedAtUtc = FETCHED_AT,
      )

    assertEquals(listOf("2026-08-07", "2026-08-08", "2026-08-09"), summaries.map { it.date })
  }

  /** The day is the primary key. A document without one cannot be addressed, so it is not stored. */
  @Test
  fun `a document with no day is dropped`() {
    val summaries =
      OuraMappers.toDailySummaries(
        readiness = listOf(day(null, 66), day("", 70), day("2026-08-07", 80)),
        sleep = emptyList(),
        activity = emptyList(),
        fetchedAtUtc = FETCHED_AT,
      )

    assertEquals(listOf("2026-08-07"), summaries.map { it.date })
  }

  @Test
  fun `no documents at all is no rows, not a row of nulls`() {
    val summaries =
      OuraMappers.toDailySummaries(emptyList(), emptyList(), emptyList(), FETCHED_AT)

    assertEquals(emptyList<Any>(), summaries)
  }

  // ------------------------------------------------------------------ workouts

  /**
   * The offset in the timestamp is the one the workout happened in. Dropping it would move an
   * evening session by two or three hours depending on the season, which is exactly how a matcher
   * ends up pairing a run with the wrong day.
   */
  @Test
  fun `a workout's offset is applied rather than ignored`() {
    val entities =
      OuraMappers.toWorkouts(
        listOf(
          workout(
            id = "w1",
            start = "2026-08-08T18:00:00.000000+03:00",
            end = "2026-08-08T18:42:11.000000+03:00",
          )
        )
      )

    val entity = entities.single()
    assertEquals(Instant.parse("2026-08-08T15:00:00Z").toEpochMilli(), entity.startTimeUtc)
    assertEquals(Instant.parse("2026-08-08T15:42:11Z").toEpochMilli(), entity.endTimeUtc)
  }

  @Test
  fun `a workout keeps its activity, calories and nothing it was not given`() {
    val entities =
      OuraMappers.toWorkouts(listOf(workout(id = "w1", activity = "running", calories = 431.0)))

    val entity = entities.single()
    assertEquals("running", entity.activityType)
    assertEquals(431.0f, entity.calories!!, 0.001f)
    // Matching a workout to a planned session is a training-domain decision, not a parser's.
    assertNull(entity.matchedSessionId)
  }

  @Test
  fun `a workout without calories keeps none`() {
    val entities = OuraMappers.toWorkouts(listOf(workout(id = "w1", calories = null)))

    assertNull(entities.single().calories)
  }

  /** These rows exist to be compared against planned sessions by time. One that cannot be placed
   * on the clock cannot take part in that, and a guessed timestamp would be worse than no row. */
  @Test
  fun `a workout with an unparseable timestamp is dropped`() {
    val entities =
      OuraMappers.toWorkouts(
        listOf(
          workout(id = "w1", start = "eilen illalla"),
          workout(id = "w2", start = null),
          workout(id = "w3", end = null),
          workout(id = "w4"),
        )
      )

    assertEquals(listOf("w4"), entities.map { it.id })
  }

  @Test
  fun `a workout with no id is dropped`() {
    val entities = OuraMappers.toWorkouts(listOf(workout(id = null), workout(id = "  ")))

    assertEquals(emptyList<Any>(), entities)
  }

  /** `activity` is required by the specification; a service that omits it still gets a row. */
  @Test
  fun `a workout with no activity is stored as unknown rather than dropped`() {
    val entities = OuraMappers.toWorkouts(listOf(workout(id = "w1", activity = null)))

    assertEquals("unknown", entities.single().activityType)
  }

  private fun day(day: String?, score: Int?) =
    OuraDailyScoreDto(id = "id-$day", day = day, score = score, timestamp = "${day}T00:00:00+03:00")

  private fun workout(
    id: String?,
    activity: String? = "running",
    calories: Double? = 431.0,
    start: String? = "2026-08-08T18:00:00.000000+03:00",
    end: String? = "2026-08-08T18:42:11.000000+03:00",
  ) =
    OuraWorkoutDto(
      id = id,
      activity = activity,
      day = "2026-08-08",
      startDatetime = start,
      endDatetime = end,
      calories = calories,
      distance = 7412.3,
      intensity = "moderate",
      source = "confirmed",
      label = null,
    )

  private companion object {
    const val FETCHED_AT = 1_754_800_000_000L
  }
}
