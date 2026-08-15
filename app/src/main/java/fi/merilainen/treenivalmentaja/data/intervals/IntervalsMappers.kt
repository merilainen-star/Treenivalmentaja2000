package fi.merilainen.treenivalmentaja.data.intervals

import fi.merilainen.treenivalmentaja.data.local.entity.IntervalsActivityEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * intervals.icu's documents as this app's rows. The one rule is the Oura mappers' rule:
 * **missing is not zero**. An activity with no heart-rate sensor is a row with no heart rate.
 */
internal object IntervalsMappers {

  fun toActivities(
    activities: List<IntervalsActivityDto>,
    fetchedAtUtc: Long,
    zone: ZoneId,
  ): List<IntervalsActivityEntity> = activities.mapNotNull { it.toEntity(fetchedAtUtc, zone) }

  /**
   * `null` when the row is too incomplete to store: no id, no type, no start this app can place on
   * the clock, or no moving time. These rows exist to be compared against planned sessions by time
   * and reduced to a pace, and a row missing those cannot take part in either.
   */
  private fun IntervalsActivityDto.toEntity(
    fetchedAtUtc: Long,
    zone: ZoneId,
  ): IntervalsActivityEntity? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val sport = type?.takeIf { it.isNotBlank() } ?: return null
    val start = startInstant(zone) ?: return null
    val moving = movingTime?.takeIf { it > 0 }?.toLong() ?: return null
    return IntervalsActivityEntity(
      id = id,
      name = name?.takeIf { it.isNotBlank() },
      sportType = sport,
      startTimeUtc = start,
      movingTimeSec = moving,
      elapsedTimeSec = elapsedTime?.takeIf { it > 0 }?.toLong(),
      distanceMeters = distance?.takeIf { it > 0.0 },
      avgHeartRate = averageHeartrate?.takeIf { it > 0 },
      maxHeartRate = maxHeartrate?.takeIf { it > 0 },
      // A flat run reports 0.0, and "nousu 0 m" on screen is noise rather than a measurement.
      elevationGainMeters = totalElevationGain?.takeIf { it > 0.0 },
      calories = calories?.takeIf { it > 0 },
      trainingLoad = icuTrainingLoad?.takeIf { it > 0 },
      source = source?.takeIf { it.isNotBlank() },
      deviceName = deviceName?.takeIf { it.isNotBlank() },
      matchedSessionId = null,
      fetchedAtUtc = fetchedAtUtc,
    )
  }

  /**
   * When the activity started, as epoch millis.
   *
   * `start_date` is UTC and unambiguous, so it wins. `start_date_local` is the fallback and is
   * exactly what its name says — a wall clock with no offset — so it has to be read against the
   * device's zone. Getting that backwards would move an evening session by two or three hours
   * depending on the season, which is precisely the error that makes a matcher pair a run with the
   * wrong session.
   */
  private fun IntervalsActivityDto.startInstant(zone: ZoneId): Long? =
    parseUtc(startDate) ?: parseLocal(startDateLocal, zone)

  private fun parseUtc(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
      Instant.parse(raw).toEpochMilli()
    } catch (e: DateTimeParseException) {
      // Some responses carry a local-shaped value in this field; the local parser below is the
      // honest second attempt rather than a guess at an offset.
      null
    }
  }

  private fun parseLocal(raw: String?, zone: ZoneId): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
      LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
      null
    }
  }
}
