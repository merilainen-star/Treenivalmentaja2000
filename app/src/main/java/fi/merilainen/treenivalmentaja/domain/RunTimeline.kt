package fi.merilainen.treenivalmentaja.domain

/** Elapsed seconds from one shared origin in the watch file, including pauses. */
data class HeartRatePoint(val second: Long, val bpm: Int?)
data class SpeedPoint(val second: Long, val metersPerSecond: Double?) {
  val paceSecPerKm: Double? get() = metersPerSecond?.takeIf { it.isFinite() && it > 0 }?.let { 1000 / it }
}
data class LapWindow(val index: Int, val startSecond: Long, val endSecond: Long)
data class RunTrace(
  val heartRate: List<HeartRatePoint> = emptyList(), val laps: List<LapWindow> = emptyList(),
  val formatVersion: Int = 1, val speed: List<SpeedPoint> = emptyList(),
)

enum class RunAxis { TIME, DISTANCE, ORDER }
data class TimelineStage(val index: Int, val label: String, val phase: RunPhase, val start: Double, val end: Double,
  val lapIndex: Int? = null)
data class RunTimeline(
  val stages: List<TimelineStage>, val axis: RunAxis, val extent: Double,
  val estimated: Boolean = false, val recorded: Boolean = false,
  val heartRate: List<HeartRatePoint> = emptyList(), val paired: Boolean = false,
  val speed: List<SpeedPoint> = emptyList(),
) {
  fun stageAt(fraction: Float): Int = stages.indexOfFirst {
    val x = fraction.coerceIn(0f, .999999f) * extent
    x >= it.start && x < it.end
  }
}

fun plannedTimeline(steps: List<RunStep>): RunTimeline {
  val seconds = steps.map { step -> step.durationSec?.toDouble()
    ?: step.distanceMeters?.let { distance -> step.paceSecPerKm?.let { distance * it / 1000.0 } } }
  val axis = when {
    seconds.all { it != null && it > 0 } -> RunAxis.TIME
    steps.all { it.distanceMeters != null && it.distanceMeters > 0 } -> RunAxis.DISTANCE
    else -> RunAxis.ORDER
  }
  var cursor = 0.0
  val stages = steps.mapIndexed { index, step ->
    val width = when (axis) {
      RunAxis.TIME -> seconds[index]!!
      RunAxis.DISTANCE -> step.distanceMeters!!.toDouble()
      RunAxis.ORDER -> 1.0
    }
    TimelineStage(index, step.summary(), step.phase(), cursor, cursor + width).also { cursor += width }
  }
  return RunTimeline(stages, axis, cursor.coerceAtLeast(1.0),
    estimated = axis == RunAxis.TIME && steps.any { it.durationSec == null })
}

fun recordedTimeline(trace: RunTrace, steps: List<RunStep>): RunTimeline? {
  val windows = trace.laps.filter { it.startSecond >= 0 && it.endSecond > it.startSecond }
    .sortedBy { it.startSecond }
  // Overlapping laps cannot be drawn as an ordered phase sequence.
  val usable = windows.takeIf { it.zipWithNext().all { (a, b) -> a.endSecond <= b.startSecond } }.orEmpty()
  val paired = usable.isNotEmpty() && usable.size == steps.size &&
    usable.map { it.index } == (1..steps.size).toList()
  val samples = trace.heartRate.filter { it.second >= 0 }.distinctBy { it.second }.sortedBy { it.second }
  val speed = trace.speed.filter { it.second >= 0 }.distinctBy { it.second }.sortedBy { it.second }
  val end = maxOf(usable.maxOfOrNull { it.endSecond } ?: 0, samples.lastOrNull()?.second ?: 0,
    speed.lastOrNull()?.second ?: 0)
  if (end <= 0 || (usable.isEmpty() && samples.none { it.bpm != null } && speed.none { it.paceSecPerKm != null })) return null
  return RunTimeline(usable.mapIndexed { index, lap ->
    val step = steps.getOrNull(index).takeIf { paired }
    TimelineStage(index, step?.summary() ?: "Kierros ${lap.index}", step?.phase() ?: RunPhase.UNKNOWN,
      lap.startSecond.toDouble(), lap.endSecond.toDouble(), lapIndex = lap.index)
  }, RunAxis.TIME, end.toDouble(), recorded = true, heartRate = samples, paired = paired, speed = speed)
}

/** Lap time/distance is authoritative; never average instantaneous pace samples. */
fun TimelineStage.averagePace(laps: List<RunLap>): Int? =
  lapIndex?.let { id -> laps.singleOrNull { it.index == id }?.paceSecPerKm }

fun List<SpeedPoint>.paceSegments(): List<List<SpeedPoint>> {
  val segments = mutableListOf<MutableList<SpeedPoint>>()
  var segment = mutableListOf<SpeedPoint>()
  for (point in this) {
    if (point.paceSecPerKm == null || (segment.isNotEmpty() && point.second - segment.last().second > 15)) {
      if (segment.isNotEmpty()) segments += segment
      segment = mutableListOf()
    }
    if (point.paceSecPerKm != null) segment += point
  }
  if (segment.isNotEmpty()) segments += segment
  return segments
}

/** Break the line at missing samples or recording gaps; never interpolate a lost HR signal. */
fun List<HeartRatePoint>.heartRateSegments(): List<List<HeartRatePoint>> {
  val segments = mutableListOf<MutableList<HeartRatePoint>>()
  var segment = mutableListOf<HeartRatePoint>()
  for (point in this) {
    if (point.bpm == null || (segment.isNotEmpty() && point.second - segment.last().second > 15)) {
      if (segment.isNotEmpty()) segments += segment
      segment = mutableListOf()
    }
    if (point.bpm != null) segment += point
  }
  if (segment.isNotEmpty()) segments += segment
  return segments
}
