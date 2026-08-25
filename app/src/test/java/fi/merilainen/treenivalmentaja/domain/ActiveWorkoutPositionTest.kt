package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored position is what makes an interrupted workout resume. It survives a round trip, and
 * anything it cannot read starts the workout rather than refusing to open it.
 */
class ActiveWorkoutPositionTest {
  @Test
  fun `a position survives being written and read back`() {
    val position = ActiveWorkoutPosition("s-1", stepIndex = 7, skippedKeys = listOf("1:2", "2:1"))

    assertEquals(position, ActiveWorkoutPosition.parse(position.encode()))
  }

  @Test
  fun `a position with nothing skipped survives too`() {
    val position = ActiveWorkoutPosition("s-1", stepIndex = 0)

    assertEquals(position, ActiveWorkoutPosition.parse(position.encode()))
  }

  @Test
  fun `movement keys contain a colon, so neither separator can appear inside a value`() {
    val encoded = ActiveWorkoutPosition("s-1", 3, listOf("1:1", "10:4")).encode()

    assertEquals("s-1|3|1:1,10:4", encoded)
  }

  @Test
  fun `anything unreadable is no stored position rather than a crash`() {
    assertNull(ActiveWorkoutPosition.parse(null))
    assertNull(ActiveWorkoutPosition.parse(""))
    assertNull(ActiveWorkoutPosition.parse("s-1|3"))
    assertNull(ActiveWorkoutPosition.parse("s-1|kolme|"))
    assertNull(ActiveWorkoutPosition.parse("|3|"))
    assertNull(ActiveWorkoutPosition.parse("s-1|-1|"))
  }
}
