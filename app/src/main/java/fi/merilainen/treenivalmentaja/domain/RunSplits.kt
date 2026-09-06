package fi.merilainen.treenivalmentaja.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One kilometre of a run, as it was actually run.
 *
 * The last one is usually short — a 9.6 km run has nine whole kilometres and a 600 m tail — and it
 * is kept at its real length rather than padded or dropped, with [distanceMeters] saying how long
 * it was. A pace computed over 600 m is a real pace; a pace computed over 600 m and *presented* as
 * a kilometre is not.
 */
data class RunSplit(
  /** 1-based. The first kilometre is split 1. */
  val index: Int,
  val distanceMeters: Int,
  val durationSec: Long,
  /** Mean of the heart-rate samples inside this split. `null` when the strap recorded none. */
  val avgHeartRate: Int? = null,
  /** Metres climbed within this split, when the recording carried altitude. */
  val elevationGainMeters: Int? = null,
) {

  /** Seconds per kilometre over this split's own distance. */
  val paceSecPerKm: Int?
    get() = distanceMeters.takeIf { it > 0 }?.let { (durationSec * 1000.0 / it).roundToInt() }

  /** `5:39` — no `/km` suffix, because the unit is stated once in the heading. */
  val paceText: String?
    get() = paceSecPerKm?.let { "%d:%02d".format(it / 60, it % 60) }

  /** A tail shorter than a whole split, which has to be labelled as one wherever it is shown. */
  val isPartial: Boolean
    get() = distanceMeters < SPLIT_METERS - PARTIAL_SLACK_METERS
}

/** A kilometre, because that is the unit the runner's watch beeps in and reads pace in. */
const val SPLIT_METERS = 1000

/** Within twenty metres of a kilometre is a kilometre; GPS is not surveying equipment. */
private const val PARTIAL_SLACK_METERS = 20

/**
 * A tail shorter than this is rounding, not a split. 200 m of a 9.6 km run is worth a line; the
 * 4 m a watch adds while you stop the recording is not.
 */
private const val MINIMUM_TAIL_METERS = 200

/**
 * A stream longer than this is not a run this app has anything useful to say about, split by split.
 * The cap is a guard against a corrupt distance channel producing a list with no end, not a limit
 * anyone should ever meet: it is more than four marathons.
 */
private const val MAX_SPLITS = 180

/**
 * Cuts a recorded run into kilometres.
 *
 * **This is the app doing the arithmetic so the model does not have to guess.** intervals.icu
 * publishes no split endpoint — the whole activity has one average pace and one average heart rate
 * — so every analysis so far has had to say it could not tell whether a run that averaged 5:35
 * started calmly and finished calmly, or started at 5:00 and fell apart. The channels contain the
 * answer; this turns them into it.
 *
 * The channels are parallel arrays, aligned by position, and any sample can be null: a heart-rate
 * strap that dropped out for a minute leaves holes rather than zeros. Samples missing a time or a
 * distance are skipped entirely — there is nowhere to put them — and a missing heart rate simply
 * does not join that split's mean.
 *
 * The time at each kilometre mark is **interpolated** between the two samples that straddle it,
 * rather than taken from whichever sample happens to land past it. At one sample a second and
 * 3 m/s that is worth up to three seconds a kilometre, which is the difference between splits that
 * look ragged and splits that show what the runner actually did.
 *
 * Heart rate and climb are attributed to the split a sample falls in, whole, without being divided
 * at the boundary. Over a kilometre of samples one straddling sample changes a mean by nothing
 * anybody could read.
 *
 * @param timeSec seconds since the start of the recording, one per sample.
 * @param distanceMeters cumulative metres, one per sample. Need not start at zero.
 * @return the splits in order, or empty when the channels cannot produce one — fewer than two
 *   usable samples, or a run shorter than a single split with no tail worth reporting.
 */
fun kilometreSplits(
  timeSec: List<Double?>,
  distanceMeters: List<Double?>,
  heartRate: List<Double?>? = null,
  altitude: List<Double?>? = null,
  splitMeters: Int = SPLIT_METERS,
): List<RunSplit> {
  val samples = minOf(timeSec.size, distanceMeters.size)
  if (samples < 2 || splitMeters <= 0) return emptyList()

  val splits = mutableListOf<RunSplit>()
  var started = false
  var previousTime = 0.0
  var previousDistance = 0.0
  var splitStartTime = 0.0
  var boundary = 0.0
  var heartRateSum = 0.0
  var heartRateSamples = 0
  var climb = 0.0
  var previousAltitude: Double? = null
  // Counted separately from `splits.size`, because a boundary whose duration comes out as zero —
  // a paused recording that resumed a kilometre later — is not written but has still been passed.
  // Numbering by position would then call the fourth kilometre the third.
  var splitNumber = 1

  for (i in 0 until samples) {
    val time = timeSec[i] ?: continue
    val distance = distanceMeters[i] ?: continue
    if (!started) {
      started = true
      splitStartTime = time
      previousTime = time
      previousDistance = distance
      boundary = distance + splitMeters
      previousAltitude = altitude?.getOrNull(i)
      continue
    }

    altitude?.getOrNull(i)?.let { current ->
      previousAltitude?.let { if (current > it) climb += current - it }
      previousAltitude = current
    }
    heartRate?.getOrNull(i)?.takeIf { it > 0.0 }?.let {
      heartRateSum += it
      heartRateSamples++
    }

    while (distance >= boundary && distance > previousDistance) {
      val fraction = (boundary - previousDistance) / (distance - previousDistance)
      val boundaryTime = previousTime + fraction * (time - previousTime)
      val duration = (boundaryTime - splitStartTime).roundToLong()
      if (duration > 0) {
        splits +=
          RunSplit(
            index = splitNumber,
            distanceMeters = splitMeters,
            durationSec = duration,
            avgHeartRate =
              heartRateSamples.takeIf { it > 0 }?.let { (heartRateSum / it).roundToInt() },
            elevationGainMeters = climb.takeIf { it >= 1.0 }?.roundToInt(),
          )
      }
      splitNumber++
      splitStartTime = boundaryTime
      boundary += splitMeters
      heartRateSum = 0.0
      heartRateSamples = 0
      climb = 0.0
      if (splits.size >= MAX_SPLITS) return splits
    }

    previousTime = time
    previousDistance = distance
  }

  if (!started) return splits
  val tail = (previousDistance - (boundary - splitMeters)).roundToInt()
  val tailDuration = (previousTime - splitStartTime).roundToLong()
  if (tail >= MINIMUM_TAIL_METERS && tailDuration > 0) {
    splits +=
      RunSplit(
        index = splitNumber,
        distanceMeters = tail,
        durationSec = tailDuration,
        avgHeartRate = heartRateSamples.takeIf { it > 0 }?.let { (heartRateSum / it).roundToInt() },
        elevationGainMeters = climb.takeIf { it >= 1.0 }?.roundToInt(),
      )
  }
  return splits
}
