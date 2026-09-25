package fi.merilainen.treenivalmentaja.data.intervals

import java.io.ByteArrayOutputStream

/**
 * Just enough of the FIT encoding to write the messages the lap tests need: a lap definition whose
 * fields are the ones a Suunto writes, record messages for the reader to step over, developer
 * fields and a compressed-timestamp header. Synthetic on purpose — a real recording carries the
 * athlete's GPS track, which has no business in a repository.
 */
class FitBuilder {
  private val data = ByteArrayOutputStream()

  fun lapDefinition(local: Int) = apply {
    data.write(0x40 or local)
    data.write(byteArrayOf(0, 0, 19, 0, 5))
    // timestamp (253, uint32) first, as real files have it, so the reader must skip it.
    data.write(byteArrayOf(253.toByte(), 4, 0x86.toByte()))
    data.write(byteArrayOf(8, 4, 0x86.toByte()))
    data.write(byteArrayOf(9, 4, 0x86.toByte()))
    data.write(byteArrayOf(15, 1, 2))
    data.write(byteArrayOf(16, 1, 2))
  }

  /** A `record` message (global 20): the per-second samples the reader must step over. */
  fun recordDefinition(local: Int, developerBytes: Int = 0) = apply {
    data.write(0x40 or local or (if (developerBytes > 0) 0x20 else 0))
    data.write(byteArrayOf(0, 0, 20, 0, 2))
    data.write(byteArrayOf(0, 4, 0x85.toByte())) // position_lat
    data.write(byteArrayOf(3, 1, 2)) // heart_rate
    if (developerBytes > 0) {
      data.write(1)
      data.write(byteArrayOf(0, developerBytes.toByte(), 0))
    }
  }

  fun record(local: Int, developerBytes: Int = 0) = apply {
    data.write(local)
    data.write(ByteArray(5 + developerBytes) { 0x11 })
  }

  fun compressedRecord(local: Int, developerBytes: Int = 0) = apply {
    data.write(0x80 or (local shl 5) or 3)
    data.write(ByteArray(5 + developerBytes) { 0x22 })
  }

  fun lap(local: Int, timerMs: Long, distanceCm: Long, avgHr: Int, maxHr: Int?) = apply {
    data.write(local)
    data.write(u32(0x3F000000))
    data.write(u32(timerMs))
    data.write(u32(distanceCm))
    data.write(avgHr)
    data.write(maxHr ?: 0xFF)
  }

  fun build(): ByteArray {
    val body = data.toByteArray()
    val header = ByteArrayOutputStream()
    header.write(14)
    header.write(0x20)
    header.write(byteArrayOf(0x08, 0x08))
    header.write(u32(body.size.toLong()))
    header.write(".FIT".toByteArray())
    header.write(byteArrayOf(0, 0))
    return header.toByteArray() + body + byteArrayOf(0, 0)
  }

  private fun u32(v: Long) =
    byteArrayOf(
      (v and 0xff).toByte(),
      ((v shr 8) and 0xff).toByte(),
      ((v shr 16) and 0xff).toByte(),
      ((v shr 24) and 0xff).toByte(),
    )
}
