package fi.merilainen.treenivalmentaja.domain

import kotlin.math.roundToInt

/**
 * One heart-rate zone and how long the session spent in it.
 *
 * [lowBpm] is derived — intervals.icu publishes only the upper bounds, and the lower bound of a
 * zone is the previous zone's upper plus one. The first zone has none: it starts wherever the
 * athlete's heart was.
 */
data class HeartRateZoneTime(
  /** 1-based, the way a zone is spoken about. */
  val zone: Int,
  /** `null` for zone 1, which has no floor worth stating. */
  val lowBpm: Int?,
  /** `null` when intervals.icu sent times without a zone table. */
  val highBpm: Int?,
  val seconds: Long,
)

/**
 * A session's whole zone distribution — the answer to "was the easy run actually easy".
 *
 * **This exists because an average hides its own composition.** Thirty minutes at 130 with ten at
 * 175 averages to the same number as forty minutes at 141, and only one of those is the session
 * that was planned. Every per-session analysis this app has produced so far has had to say so;
 * this is what it needed instead.
 *
 * Constructed only through [heartRateZones], which is where the rules about mismatched or missing
 * arrays live.
 */
data class HeartRateZones(val zones: List<HeartRateZoneTime>) {

  val totalSeconds: Long
    get() = zones.sumOf { it.seconds }

  /**
   * That zone's share of the session, 0–100.
   *
   * `null` when nothing was recorded at all, rather than 0 — a session with no heart-rate data has
   * no distribution, and calling that "0 % in every zone" would be a measurement the app did not
   * make.
   */
  fun percentOf(zone: HeartRateZoneTime): Int? {
    val total = totalSeconds
    if (total <= 0L) return null
    return (zone.seconds * 100.0 / total).roundToInt()
  }

  /** `Z2 (124–145)`, or `Z2` when there is no zone table to name the bounds with. */
  fun label(zone: HeartRateZoneTime): String {
    val low = zone.lowBpm
    val high = zone.highBpm
    return when {
      high == null -> "Z${zone.zone}"
      low == null -> "Z${zone.zone} (–$high)"
      else -> "Z${zone.zone} ($low–$high)"
    }
  }
}

/**
 * Reads intervals.icu's two parallel arrays into zones, or decides there is nothing to read.
 *
 * `null` — not an empty distribution — whenever the answer would be a fabrication:
 *
 *  * no times at all, or every time zero: the session has no heart-rate record;
 *  * more times than the zone table has entries. The two arrays are meant to be parallel, and a
 *    longer times array means this app does not understand the pairing. Guessing an alignment
 *    would put minutes in the wrong zone, which is worse than saying nothing.
 *
 * A **shorter** times array is fine and is padded with zeros: intervals.icu trims trailing zeros on
 * some activities, and "no seconds in Z5" is a real reading rather than a missing one. A missing
 * zone table is also fine — the times still say how the effort was distributed, and the zones are
 * simply left unnamed.
 */
fun heartRateZones(upperBoundsBpm: List<Int>?, secondsPerZone: List<Int>?): HeartRateZones? {
  val times = secondsPerZone.orEmpty()
  if (times.isEmpty() || times.all { it <= 0 }) return null
  val bounds = upperBoundsBpm.orEmpty()
  if (bounds.isNotEmpty() && times.size > bounds.size) return null
  val count = maxOf(times.size, bounds.size)
  val zones =
    (0 until count).map { index ->
      HeartRateZoneTime(
        zone = index + 1,
        lowBpm = bounds.getOrNull(index - 1)?.plus(1),
        highBpm = bounds.getOrNull(index),
        seconds = times.getOrNull(index)?.coerceAtLeast(0)?.toLong() ?: 0L,
      )
    }
  return HeartRateZones(zones)
}
