package fi.merilainen.treenivalmentaja.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The list converter added for the heart-rate zone columns.
 *
 * The enum converters above it are covered by every Room test in the suite; this one is worth its
 * own file because it is the only converter in the app that can lose information, and the two cases
 * it must keep apart — no list and an empty list — are exactly the ones a `split(",")` gets wrong
 * without being told.
 */
class ConvertersTest {

  private val converters = Converters()

  @Test
  fun `a list of zone bounds round-trips`() {
    val stored = converters.intListToString(listOf(123, 145, 160, 172, 190))

    assertEquals("123,145,160,172,190", stored)
    assertEquals(listOf(123, 145, 160, 172, 190), converters.stringToIntList(stored))
  }

  @Test
  fun `no list and an empty list stay different facts`() {
    assertNull(converters.intListToString(null))
    assertNull(converters.stringToIntList(null))
    assertEquals("", converters.intListToString(emptyList()))
    assertEquals(emptyList<Int>(), converters.stringToIntList(""))
  }

  @Test
  fun `a single value needs no separator`() {
    assertEquals("190", converters.intListToString(listOf(190)))
    assertEquals(listOf(190), converters.stringToIntList("190"))
  }

  /** A boundary of 0 bpm would be a measurement; a value this cannot read is the absence of one. */
  @Test
  fun `an unreadable entry is dropped rather than read as zero`() {
    assertEquals(listOf(123, 160), converters.stringToIntList("123,,160"))
    assertEquals(listOf(123, 160), converters.stringToIntList("123,kolme,160"))
  }

  @Test
  fun `whitespace around a value does not stop it being read`() {
    assertEquals(listOf(123, 145), converters.stringToIntList(" 123 , 145 "))
  }

  @Test
  fun `zero is a real value and survives`() {
    assertEquals(listOf(240, 1800, 0), converters.stringToIntList("240,1800,0"))
  }
}
