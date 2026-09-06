package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules about reading intervals.icu's two parallel zone arrays, and in particular the two
 * places where the honest answer is "nothing".
 */
class HeartRateZonesTest {

  @Test
  fun `pairs each zone with its own beat range`() {
    val zones = heartRateZones(listOf(123, 145, 160, 172, 190), listOf(250, 1900, 600, 120, 0))!!

    assertEquals(5, zones.zones.size)
    assertEquals(null, zones.zones[0].lowBpm)
    assertEquals(123, zones.zones[0].highBpm)
    assertEquals(124, zones.zones[1].lowBpm)
    assertEquals(145, zones.zones[1].highBpm)
    assertEquals(1900L, zones.zones[1].seconds)
  }

  @Test
  fun `the first zone has no floor and the label says so`() {
    val zones = heartRateZones(listOf(123, 145), listOf(60, 60))!!

    assertEquals("Z1 (–123)", zones.label(zones.zones[0]))
    assertEquals("Z2 (124–145)", zones.label(zones.zones[1]))
  }

  @Test
  fun `shares are rounded percentages of the recorded total`() {
    val zones = heartRateZones(listOf(120, 150), listOf(300, 900))!!

    assertEquals(1200L, zones.totalSeconds)
    assertEquals(25, zones.percentOf(zones.zones[0]))
    assertEquals(75, zones.percentOf(zones.zones[1]))
  }

  @Test
  fun `no times at all is no distribution`() {
    assertNull(heartRateZones(listOf(120, 150), null))
    assertNull(heartRateZones(listOf(120, 150), emptyList()))
  }

  @Test
  fun `all-zero times is a session the strap recorded nothing for`() {
    assertNull(heartRateZones(listOf(120, 150, 170), listOf(0, 0, 0)))
  }

  @Test
  fun `more times than zones is a pairing this app does not understand`() {
    assertNull(heartRateZones(listOf(120, 150), listOf(60, 60, 60)))
  }

  @Test
  fun `fewer times than zones is padded, because a trimmed trailing zero is still a zero`() {
    val zones = heartRateZones(listOf(120, 150, 170, 180, 195), listOf(300, 900))!!

    assertEquals(5, zones.zones.size)
    assertEquals(0L, zones.zones[4].seconds)
    assertEquals(1200L, zones.totalSeconds)
    assertEquals(0, zones.percentOf(zones.zones[4]))
  }

  @Test
  fun `times without a zone table still say how the effort was distributed`() {
    val zones = heartRateZones(null, listOf(300, 900))!!

    assertEquals(2, zones.zones.size)
    assertEquals("Z1", zones.label(zones.zones[0]))
    assertEquals(75, zones.percentOf(zones.zones[1]))
  }

  @Test
  fun `a negative time is read as zero rather than subtracted from the total`() {
    val zones = heartRateZones(listOf(120, 150), listOf(600, -5))!!

    assertEquals(0L, zones.zones[1].seconds)
    assertEquals(600L, zones.totalSeconds)
  }
}
