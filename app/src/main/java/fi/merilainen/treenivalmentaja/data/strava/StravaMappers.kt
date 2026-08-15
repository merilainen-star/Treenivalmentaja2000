package fi.merilainen.treenivalmentaja.data.strava

import fi.merilainen.treenivalmentaja.data.local.entity.StravaActivityEntity
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/**
 * Strava's documents as this app's rows. The one rule is the Oura mappers' rule: **missing is not
 * zero**. A summary with no distance stays a row with no distance.
 */
internal object StravaMappers {

  fun toActivities(
    activities: List<StravaActivityDto>,
    fetchedAtUtc: Long,
  ): List<StravaActivityEntity> = activities.mapNotNull { it.toEntity(fetchedAtUtc) }

  /**
   * `null` when the row is too incomplete to store: no id, no sport, no start this app can place
   * on the clock, or no moving time. These rows exist to be compared against planned sessions by
   * time and reduced to a pace, and a row missing those cannot take part in either.
   */
  private fun StravaActivityDto.toEntity(fetchedAtUtc: Long): StravaActivityEntity? {
    val id = id ?: return null
    val sport = sportType?.takeIf { it.isNotBlank() } ?: return null
    val start = epochMillis(startDate) ?: return null
    val moving = movingTime?.takeIf { it > 0 } ?: return null
    return StravaActivityEntity(
      id = id,
      name = name?.takeIf { it.isNotBlank() },
      sportType = sport,
      startTimeUtc = start,
      movingTimeSec = moving,
      elapsedTimeSec = elapsedTime?.takeIf { it > 0 },
      distanceMeters = distance?.takeIf { it > 0.0 },
      avgHeartRate = averageHeartrate?.takeIf { it > 0.0 }?.roundToInt(),
      maxHeartRate = maxHeartrate?.takeIf { it > 0.0 }?.roundToInt(),
      elevationGainMeters = totalElevationGain?.takeIf { it > 0.0 },
      matchedSessionId = null,
      fetchedAtUtc = fetchedAtUtc,
    )
  }

  /** Strava's `start_date` is UTC with a trailing `Z` — `Instant.parse` reads exactly that. */
  private fun epochMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
      Instant.parse(raw).toEpochMilli()
    } catch (e: DateTimeParseException) {
      null
    }
  }
}
