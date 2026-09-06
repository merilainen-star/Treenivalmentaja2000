package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kilometre splits the app computes so the model does not have to guess at them.
 *
 * The fixtures are built rather than recorded: a run at a stated pace is a channel this test can
 * generate exactly, which makes "did the interpolation put the boundary in the right place" an
 * assertion rather than an eyeball.
 */
class RunSplitsTest {

  /** One sample a second at a constant pace, the way a watch records. */
  private fun steady(
    metresPerSecond: Double,
    seconds: Int,
    heartRate: Int? = null,
  ): Triple<List<Double?>, List<Double?>, List<Double?>?> =
    Triple(
      (0..seconds).map { it.toDouble() },
      (0..seconds).map { it * metresPerSecond },
      heartRate?.let { hr -> (0..seconds).map { hr.toDouble() } },
    )

  @Test
  fun `a steady run splits into equal kilometres`() {
    val (time, distance) = steady(metresPerSecond = 3.0, seconds = 1000)

    val splits = kilometreSplits(time, distance)

    assertEquals(3, splits.size)
    assertEquals(listOf(1, 2, 3), splits.map { it.index })
    splits.forEach {
      assertEquals(1000, it.distanceMeters)
      assertEquals(333L, it.durationSec)
      assertFalse(it.isPartial)
    }
  }

  @Test
  fun `pace is reported per kilometre`() {
    val (time, distance) = steady(metresPerSecond = 1000.0 / 300.0, seconds = 600)

    val splits = kilometreSplits(time, distance)

    assertEquals(2, splits.size)
    assertEquals("5:00", splits.first().paceText)
    assertEquals(300, splits.first().paceSecPerKm)
  }

  @Test
  fun `the boundary time is interpolated, not taken from the next sample`() {
    // 300 m between samples, so the kilometre mark falls a third of the way through the last gap.
    // Interpolated, the first kilometre took 100 s; charged the whole straddling sample it would
    // read 115 — a 15-second error on one kilometre.
    val time = listOf(0.0, 30.0, 60.0, 90.0, 115.0)
    val distance = listOf(0.0, 300.0, 600.0, 900.0, 1150.0)

    val splits = kilometreSplits(time, distance)

    assertEquals(1, splits.size)
    assertEquals(100L, splits.single().durationSec)
  }

  @Test
  fun `a short tail is kept at its real length and marked partial`() {
    val (time, distance) = steady(metresPerSecond = 3.0, seconds = 500)

    val splits = kilometreSplits(time, distance)

    assertEquals(2, splits.size)
    val tail = splits.last()
    assertEquals(2, tail.index)
    assertEquals(500, tail.distanceMeters)
    assertTrue(tail.isPartial)
  }

  @Test
  fun `a tail of a few metres is rounding, not a split`() {
    val (time, distance) = steady(metresPerSecond = 3.0, seconds = 340)

    val splits = kilometreSplits(time, distance)

    assertEquals(1, splits.size)
    assertFalse(splits.single().isPartial)
  }

  @Test
  fun `a run inside one kilometre with a real tail still reports it`() {
    val (time, distance) = steady(metresPerSecond = 3.0, seconds = 200)

    val splits = kilometreSplits(time, distance)

    assertEquals(1, splits.size)
    assertEquals(600, splits.single().distanceMeters)
    assertTrue(splits.single().isPartial)
  }

  @Test
  fun `heart rate is averaged inside each split`() {
    val time = (0..2000).map { it.toDouble() }
    val distance = (0..2000).map { it * 1.0 }
    val heartRate = (0..2000).map { if (it <= 1000) 130.0 else 170.0 }

    val splits = kilometreSplits(time, distance, heartRate = heartRate)

    assertEquals(2, splits.size)
    assertEquals(130, splits[0].avgHeartRate)
    assertEquals(170, splits[1].avgHeartRate)
  }

  @Test
  fun `a strap that dropped out leaves the split without a heart rate, never with a zero`() {
    val time = (0..1000).map { it.toDouble() }
    val distance = (0..1000).map { it * 1.0 }
    val heartRate: List<Double?> = (0..1000).map { null }

    val splits = kilometreSplits(time, distance, heartRate = heartRate)

    assertEquals(null, splits.single().avgHeartRate)
  }

  @Test
  fun `climb counts only the metres actually gained`() {
    val time = (0..1000).map { it.toDouble() }
    val distance = (0..1000).map { it * 1.0 }
    // Up thirty metres over the first half, back down over the second: a net zero, a gain of 30.
    val altitude = (0..1000).map { if (it <= 500) 100.0 + it * 0.06 else 130.0 - (it - 500) * 0.06 }

    val splits = kilometreSplits(time, distance, altitude = altitude)

    assertEquals(30, splits.single().elevationGainMeters)
  }

  @Test
  fun `a flat run reports no climb rather than zero metres`() {
    val time = (0..1000).map { it.toDouble() }
    val distance = (0..1000).map { it * 1.0 }
    val altitude = (0..1000).map { 100.0 }

    val splits = kilometreSplits(time, distance, altitude = altitude)

    assertEquals(null, splits.single().elevationGainMeters)
  }

  @Test
  fun `samples missing a time or a distance are skipped, not read as zero`() {
    val time = (0..1000).map { if (it == 400) null else it.toDouble() }
    val distance: List<Double?> = (0..1000).map { if (it == 700) null else it * 1.0 }

    val splits = kilometreSplits(time, distance)

    assertEquals(1, splits.size)
    assertEquals(1000L, splits.single().durationSec)
  }

  @Test
  fun `a distance channel that does not start at zero is still split from where it starts`() {
    val time = (0..2000).map { it.toDouble() }
    val distance = (0..2000).map { 5000.0 + it }

    val splits = kilometreSplits(time, distance)

    assertEquals(2, splits.size)
    assertEquals(listOf(1, 2), splits.map { it.index })
  }

  @Test
  fun `a kilometre crossed in no time is left out but still counted`() {
    // A GPS jump: the recording moves from 900 m to 2100 m without the clock advancing, so the
    // second kilometre has no duration to report. It must not be written — and the kilometre after
    // it must still be called the third.
    val time = listOf(0.0, 10.0, 10.0, 20.0)
    val distance = listOf(0.0, 900.0, 2100.0, 3000.0)

    val splits = kilometreSplits(time, distance)

    assertEquals(listOf(1, 3), splits.map { it.index })
  }

  @Test
  fun `a gap in the recording is charged to the kilometre it happened in`() {
    // The watch recorded 0–500 m, then nothing until 3500 m ten minutes later.
    val time = listOf(0.0, 100.0, 700.0, 1700.0)
    val distance = listOf(0.0, 500.0, 3500.0, 4500.0)

    val splits = kilometreSplits(time, distance)

    assertEquals(listOf(1, 2, 3, 4, 5), splits.map { it.index })
    assertEquals(500, splits.last().distanceMeters)
    assertTrue(splits.last().isPartial)
  }

  @Test
  fun `fewer than two usable samples produces nothing`() {
    assertEquals(emptyList<RunSplit>(), kilometreSplits(listOf(0.0), listOf(0.0)))
    assertEquals(emptyList<RunSplit>(), kilometreSplits(emptyList(), emptyList()))
  }

  @Test
  fun `a distance channel that never moves produces nothing`() {
    val time = (0..1000).map { it.toDouble() }
    val distance = (0..1000).map { 0.0 }

    assertEquals(emptyList<RunSplit>(), kilometreSplits(time, distance))
  }

  @Test
  fun `the split distance is a parameter, so a different unit is one argument away`() {
    val time = (0..1000).map { it.toDouble() }
    val distance = (0..1000).map { it * 1.0 }

    val splits = kilometreSplits(time, distance, splitMeters = 500)

    assertEquals(2, splits.size)
    assertEquals(500, splits.first().distanceMeters)
  }
}
