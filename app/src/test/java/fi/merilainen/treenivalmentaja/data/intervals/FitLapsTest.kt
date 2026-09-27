package fi.merilainen.treenivalmentaja.data.intervals

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lap reader against files built byte by byte here.
 *
 * Synthetic rather than a real recording on purpose: a real FIT file carries the athlete's GPS
 * track, and that has no business in a repository. What these files do reproduce is the shape of
 * the Suunto file this feature was written against — lap messages interleaved with record messages
 * the reader must skip, developer fields, and a compressed-timestamp header.
 */
class FitLapsTest {
  @Test fun `speed uses FIT scaling enhanced precedence and invalid fallback in either endian`() {
    for (big in listOf(false, true)) {
      val trace = FitLaps.readRecording(FitBuilder().speedDefinition(0, bigEndian = big)
        .speed(0, 1000, 3000, 4000, big).speed(0, 1001, 2500, bigEndian = big)
        .speed(0, 1002, 0xffff, bigEndian = big).speed(0, 1003, 0, bigEndian = big).build()).trace
      assertEquals(listOf(4.0, 2.5, null, 0.0), trace.speed.map { it.metersPerSecond })
      assertEquals(listOf(250.0, 400.0, null, null), trace.speed.map { it.paceSecPerKm })
      assertEquals(trace.heartRate.map { it.second }, trace.speed.map { it.second })
      assertEquals(trace, RunTraceJson.decode(RunTraceJson.encode(trace)))
      assertTrue(RunTraceJson.isCurrent(RunTraceJson.encode(trace)))
    }
  }
  @Test fun `compressed speed and HR use the same clock across rollover`() {
    val trace = FitLaps.readRecording(FitBuilder().speedDefinition(0).speed(0, 1022, 3000)
      .speedDefinition(1, timestamp = false).speed(1, 0, 4000, compressedOffset = 1).build()).trace
    assertEquals(listOf(0L, 3L), trace.speed.map { it.second })
    assertEquals(listOf(0L, 3L), trace.heartRate.map { it.second })
  }
  @Test fun `legacy HR cache remains readable but needs speed backfill`() {
    val json = """{"heartRate":[{"second":0,"bpm":123}],"laps":[]}"""
    assertEquals(123, RunTraceJson.decode(json)!!.heartRate.single().bpm)
    assertFalse(RunTraceJson.isCurrent(json))
    assertFalse(RunTraceJson.isCurrent("invalid"))
    assertTrue(RunTraceJson.isCurrent(RunTraceJson.encode(FitLaps.readRecording(byteArrayOf()).trace)))
  }

  @Test fun `curve and lap boundaries share elapsed clock including pauses`() {
    val bytes = FitBuilder().timedLapDefinition(0).heartDefinition(1)
      .heart(1, 1000, 100).heart(1, 1720, 150).heart(1, 1721, 255)
      .timedLap(0, 1000, 1730, 720_000).timedLap(0, 1730, 1742, 12_000).build()
    val result = FitLaps.readRecording(bytes)
    assertEquals(720_000L, result.laps[0].durationMs)
    assertEquals(730L, result.trace.laps[0].endSecond)
    assertEquals(730L, result.trace.laps[1].startSecond)
    assertEquals(listOf(0L, 720L, 721L), result.trace.heartRate.map { it.second })
    assertNull(result.trace.heartRate.last().bpm)
    assertEquals(result.trace, RunTraceJson.decode(RunTraceJson.encode(result.trace)))
  }
  @Test fun `compressed time rollover follows last absolute timestamp in either endian`() {
    for (bigEndian in listOf(false, true)) {
      val bytes = FitBuilder().heartDefinition(1, bigEndian = bigEndian)
        .heart(1, 1022, 110, bigEndian).heartDefinition(2, timestamp = false)
        .compressedHeart(2, 31, 120).compressedHeart(2, 1, 130).build()
      val trace = FitLaps.readRecording(bytes).trace
      assertEquals(listOf(0L, 1L, 3L), trace.heartRate.map { it.second })
      assertEquals(listOf(110, 120, 130), trace.heartRate.map { it.bpm })
    }
  }

  @Test
  fun `laps are read in order with their timer time, distance and heart rate`() {
    val fit =
      FitBuilder()
        .lapDefinition(local = 0)
        .recordDefinition(local = 1)
        .record(local = 1)
        .lap(local = 0, timerMs = 720_000, distanceCm = 215_400, avgHr = 135, maxHr = 141)
        .record(local = 1)
        .lap(local = 0, timerMs = 116_600, distanceCm = 40_000, avgHr = 150, maxHr = 158)
        .build()

    val laps = FitLaps.read(fit)

    assertEquals(2, laps.size)
    assertEquals(1, laps[0].index)
    assertEquals(720_000L, laps[0].durationMs)
    assertEquals(2154.0, laps[0].distanceMeters!!, 0.001)
    assertEquals(135, laps[0].avgHeartRate)
    assertEquals(141, laps[0].maxHeartRate)
    assertEquals(2, laps[1].index)
    assertEquals("1:56,6", laps[1].durationText)
    assertEquals("400 m", laps[1].distanceText)
    // 116.6 s over 400 m is 291.5 s/km.
    assertEquals(292, laps[1].paceSecPerKm)
  }

  @Test
  fun `developer fields and compressed timestamps are skipped by their declared sizes`() {
    val fit =
      FitBuilder()
        .lapDefinition(local = 0)
        .recordDefinition(local = 1, developerBytes = 3)
        .record(local = 1, developerBytes = 3)
        .compressedRecord(local = 1, developerBytes = 3)
        .lap(local = 0, timerMs = 90_000, distanceCm = 15_530, avgHr = 138, maxHr = null)
        .build()

    val laps = FitLaps.read(fit)

    assertEquals(1, laps.size)
    assertEquals(90_000L, laps[0].durationMs)
    assertNull("0xFF is FIT's invalid marker, not a heart rate", laps[0].maxHeartRate)
  }

  @Test
  fun `a gzipped or zipped file is unwrapped first`() {
    val fit = FitBuilder().lapDefinition(0).lap(0, 15_000, 4_630, 137, 140).build()
    val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(fit) } }
    val zip =
      ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use {
          it.putNextEntry(ZipEntry("run.fit"))
          it.write(fit)
          it.closeEntry()
        }
      }

    assertEquals(1, FitLaps.read(gz.toByteArray()).size)
    assertEquals(1, FitLaps.read(zip.toByteArray()).size)
  }

  @Test
  fun `something that is not a FIT file has no laps rather than an error`() {
    assertTrue(FitLaps.read("[]".toByteArray()).isEmpty())
    assertTrue(FitLaps.read(ByteArray(0)).isEmpty())
  }

  @Test
  fun `a truncated file keeps the laps that were complete`() {
    val fit =
      FitBuilder()
        .lapDefinition(0)
        .lap(0, 15_000, 4_630, 137, 140)
        .lap(0, 45_000, 12_410, 140, 144)
        .build()

    // Cut into the second lap. The header still claims the full length.
    val laps = FitLaps.read(fit.copyOf(fit.size - 8))

    assertEquals(1, laps.size)
  }
}
