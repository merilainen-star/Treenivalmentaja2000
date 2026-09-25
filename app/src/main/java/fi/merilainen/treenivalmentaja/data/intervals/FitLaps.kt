package fi.merilainen.treenivalmentaja.data.intervals

import fi.merilainen.treenivalmentaja.domain.RunLap
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Reads the lap messages out of a FIT file, and nothing else.
 *
 * **Why a file parser at all.** intervals.icu computes its own intervals and ignores the watch's
 * laps: a 6 × 400 m SuuntoPlus Guide session with eighteen laps came back from it as one 40-minute
 * "Recovery" interval. The laps exist only in the file the watch uploaded, which intervals.icu
 * serves unchanged from `GET /api/v1/activity/{id}/file`.
 *
 * **Why not a library.** The whole need is one message type and six of its fields. The FIT format
 * is a flat stream of self-describing records — a definition says what the next data records of
 * its local type contain — so reading laps is walking that stream and skipping everything else by
 * the sizes the definitions declare. Nothing else in the file (the GPS track above all) is kept or
 * returned.
 *
 * **What is never trusted:** every length. A truncated or corrupt file ends the walk and returns
 * whatever laps were complete before it, rather than throwing — a file this app cannot read is a
 * run without laps, not a failed sync.
 *
 * Field numbers are from the FIT profile's `lap` message (global number 19):
 * 8 `total_timer_time` (uint32, ms), 9 `total_distance` (uint32, cm), 15 `avg_heart_rate` (uint8),
 * 16 `max_heart_rate` (uint8). Timer time rather than elapsed time (field 7), because it is the
 * figure the watch's own lap table shows.
 */
internal object FitLaps {

  /** A file larger than this is not a run's recording; refusing it bounds memory on a phone. */
  const val MAX_FILE_BYTES = 16 * 1024 * 1024

  /** More laps than this is a corrupt file, not a session. */
  private const val MAX_LAPS = 500

  private const val LAP_MESSAGE = 19
  private const val FIELD_TIMER_TIME = 8
  private const val FIELD_DISTANCE = 9
  private const val FIELD_AVG_HR = 15
  private const val FIELD_MAX_HR = 16

  /**
   * The laps in [bytes], which may be a bare `.fit`, a gzip of one, or a zip containing one — the
   * three forms an "original file" arrives in.
   *
   * @return the laps in order, or empty when the file is not FIT or has none.
   */
  fun read(bytes: ByteArray): List<RunLap> {
    val fit = unwrap(bytes) ?: return emptyList()
    return try {
      parse(fit)
    } catch (e: IndexOutOfBoundsException) {
      emptyList()
    }
  }

  private fun unwrap(bytes: ByteArray): ByteArray? =
    try {
      when {
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
          GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBounded() }
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
          ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && !entry.name.endsWith(".fit", ignoreCase = true)) {
              entry = zip.nextEntry
            }
            entry?.let { zip.readBounded() }
          }
        else -> bytes
      }
    } catch (e: IOException) {
      null
    }

  /** Reads at most [MAX_FILE_BYTES], so a hostile archive cannot inflate without bound. */
  private fun java.io.InputStream.readBounded(): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      val n = read(buffer)
      if (n < 0) break
      out.write(buffer, 0, n)
      if (out.size() > MAX_FILE_BYTES) return null
    }
    return out.toByteArray()
  }

  private class Definition(
    val global: Int,
    val bigEndian: Boolean,
    /** (field number, size in bytes) in record order. */
    val fields: List<Pair<Int, Int>>,
    val developerBytes: Int,
  ) {
    val size: Int = fields.sumOf { it.second } + developerBytes
  }

  private fun parse(fit: ByteArray): List<RunLap> {
    val laps = mutableListOf<RunLap>()
    var fileStart = 0
    // A FIT "file" may be several chained files back to back; each has its own header.
    while (fileStart + 12 <= fit.size) {
      val headerSize = fit[fileStart].toInt() and 0xff
      if (headerSize < 12 || fileStart + headerSize > fit.size) break
      if (String(fit, fileStart + 8, 4, Charsets.US_ASCII) != ".FIT") break
      val dataSize = u32(fit, fileStart + 4, bigEndian = false).toInt()
      if (dataSize < 0) break
      val end = minOf(fit.size, fileStart + headerSize + dataSize)
      val definitions = arrayOfNulls<Definition>(16)
      var pos = fileStart + headerSize
      while (pos < end) {
        val header = fit[pos].toInt() and 0xff
        pos++
        if (header and 0x80 != 0) {
          // Compressed-timestamp header: a data record, local type in bits 5–6.
          val def = definitions[(header shr 5) and 0x03] ?: return laps
          if (pos + def.size > end) return laps
          if (def.global == LAP_MESSAGE) readLap(fit, pos, def, laps.size + 1)?.let { laps += it }
          pos += def.size
        } else if (header and 0x40 != 0) {
          if (pos + 5 > end) return laps
          val bigEndian = fit[pos + 1].toInt() == 1
          val global =
            if (bigEndian) ((fit[pos + 2].toInt() and 0xff) shl 8) or (fit[pos + 3].toInt() and 0xff)
            else ((fit[pos + 3].toInt() and 0xff) shl 8) or (fit[pos + 2].toInt() and 0xff)
          val count = fit[pos + 4].toInt() and 0xff
          pos += 5
          if (pos + count * 3 > end) return laps
          val fields = (0 until count).map { i ->
            (fit[pos + i * 3].toInt() and 0xff) to (fit[pos + i * 3 + 1].toInt() and 0xff)
          }
          pos += count * 3
          var developerBytes = 0
          if (header and 0x20 != 0) {
            if (pos + 1 > end) return laps
            val devCount = fit[pos].toInt() and 0xff
            pos++
            if (pos + devCount * 3 > end) return laps
            for (i in 0 until devCount) developerBytes += fit[pos + i * 3 + 1].toInt() and 0xff
            pos += devCount * 3
          }
          definitions[header and 0x0f] = Definition(global, bigEndian, fields, developerBytes)
        } else {
          val def = definitions[header and 0x0f] ?: return laps
          if (pos + def.size > end) return laps
          if (def.global == LAP_MESSAGE) readLap(fit, pos, def, laps.size + 1)?.let { laps += it }
          pos += def.size
        }
        if (laps.size >= MAX_LAPS) return laps
      }
      // Two-byte CRC after the data.
      fileStart = end + 2
    }
    return laps
  }

  /** `null` when the lap has no timer time, which is the one field a lap cannot do without. */
  private fun readLap(fit: ByteArray, start: Int, def: Definition, index: Int): RunLap? {
    var timerMs: Long? = null
    var distanceCm: Long? = null
    var avgHr: Int? = null
    var maxHr: Int? = null
    var offset = start
    for ((field, size) in def.fields) {
      when {
        field == FIELD_TIMER_TIME && size == 4 ->
          timerMs = u32(fit, offset, def.bigEndian).takeIf { it != 0xFFFFFFFFL }
        field == FIELD_DISTANCE && size == 4 ->
          distanceCm = u32(fit, offset, def.bigEndian).takeIf { it != 0xFFFFFFFFL }
        field == FIELD_AVG_HR && size == 1 ->
          avgHr = (fit[offset].toInt() and 0xff).takeIf { it in 1..254 }
        field == FIELD_MAX_HR && size == 1 ->
          maxHr = (fit[offset].toInt() and 0xff).takeIf { it in 1..254 }
      }
      offset += size
    }
    val duration = timerMs?.takeIf { it > 0 } ?: return null
    return RunLap(
      index = index,
      durationMs = duration,
      distanceMeters = distanceCm?.let { it / 100.0 },
      avgHeartRate = avgHr,
      maxHeartRate = maxHr,
    )
  }

  private fun u32(bytes: ByteArray, at: Int, bigEndian: Boolean): Long {
    val b = IntArray(4) { bytes[at + it].toInt() and 0xff }
    return if (bigEndian)
      (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
    else (b[3].toLong() shl 24) or (b[2].toLong() shl 16) or (b[1].toLong() shl 8) or b[0].toLong()
  }
}
