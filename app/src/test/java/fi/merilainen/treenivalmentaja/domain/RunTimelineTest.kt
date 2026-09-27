package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.*
import org.junit.Test

class RunTimelineTest {
  @Test fun `lap average uses timer and distance not elapsed pauses or sample mean`() {
    val plot = recordedTimeline(RunTrace(speed = listOf(SpeedPoint(0, 2.0), SpeedPoint(600, 5.0)),
      laps = listOf(LapWindow(7, 0, 720))), emptyList())!!
    assertEquals(300, plot.stages.single().averagePace(listOf(RunLap(7, 600_000, 2000.0))))
    assertNull(plot.stages.single().averagePace(listOf(RunLap(1, 600_000, 2000.0))))
    assertNull(plot.stages.single().averagePace(listOf(RunLap(7, 600_000))))
  }
  @Test fun `pace only recording has a timeline and gaps or stops break its curve`() {
    val points = listOf(SpeedPoint(0, 3.0), SpeedPoint(1, 4.0), SpeedPoint(2, 0.0),
      SpeedPoint(3, 2.0), SpeedPoint(4, null), SpeedPoint(5, 3.0), SpeedPoint(30, 3.5))
    val plot = recordedTimeline(RunTrace(speed = points), emptyList())!!
    assertEquals(30.0, plot.extent, 0.0)
    assertEquals(listOf(2, 1, 1, 1), plot.speed.paceSegments().map { it.size })
    assertTrue(plot.heartRate.isEmpty())
  }
  @Test fun `twelve minute warmup is sixty times a twelve second acceleration`() {
    val plot = plannedTimeline(listOf(RunStep("Lämmittely", durationSec = 720), RunStep("Kiihdytys", durationSec = 12)))
    assertEquals(60.0, (plot.stages[0].end - plot.stages[0].start) / (plot.stages[1].end - plot.stages[1].start), 0.0)
    assertEquals(0, plot.stageAt(.9f)); assertEquals(1, plot.stageAt(.999f))
    assertFalse(plot.estimated)
  }
  @Test fun `distance at target pace produces a labelled time estimate`() {
    val plot = plannedTimeline(listOf(RunStep("Lämmittely", durationSec = 720), RunStep("Veto", distanceMeters = 600, paceSecPerKm = 260)))
    assertEquals(876.0, plot.extent, 0.0); assertTrue(plot.estimated)
    assertEquals(RunAxis.TIME, plot.axis)
  }
  @Test fun `missing pace never becomes invented time`() {
    assertEquals(RunAxis.DISTANCE, plannedTimeline(listOf(RunStep("Juoksu", distanceMeters = 5000))).axis)
    assertEquals(RunAxis.ORDER, plannedTimeline(listOf(RunStep("Lämmittely", durationSec = 720), RunStep("Veto", distanceMeters = 400))).axis)
  }
  @Test fun `measured windows retain pauses and unmatched laps never get plan labels`() {
    val trace = RunTrace(listOf(HeartRatePoint(0, 100), HeartRatePoint(750, 150)),
      listOf(LapWindow(1, 0, 730), LapWindow(2, 740, 760)))
    val plot = recordedTimeline(trace, listOf(RunStep("Veto", durationSec = 720)))!!
    assertEquals(760.0, plot.extent, 0.0); assertFalse(plot.paired)
    assertEquals("Kierros 2", plot.stages[1].label)
    assertEquals(-1, plot.stageAt(735f / 760))
    assertEquals(740.0, plot.stages[1].start, 0.0)
  }
  @Test fun `signal gaps and missing HR break the curve without dropping peaks`() {
    val points = listOf(HeartRatePoint(0, 100), HeartRatePoint(1, 180), HeartRatePoint(2, null),
      HeartRatePoint(3, 120), HeartRatePoint(30, 130))
    assertEquals(listOf(2, 1, 1), points.heartRateSegments().map { it.size })
    assertEquals(180, points.heartRateSegments()[0][1].bpm)
    assertNull(recordedTimeline(RunTrace(), emptyList()))
  }
}
