package fi.merilainen.treenivalmentaja

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tapping a day in the month grid moves the list underneath it. The grid offers every day of the
 * month; the list only holds the days that earned a row, so the mapping between them is where this
 * can go wrong.
 */
class WeekCalendarSelectionTest {
  private val today = LocalDate.of(2026, 8, 24)

  @Test
  fun `a day that has its own row scrolls to exactly that row`() {
    val days = listOf(-3, 0, 1, 2, 3, 4, 5, 6)

    assertEquals(0, rowIndexForDate(days, today, today.minusDays(3)))
    assertEquals(1, rowIndexForDate(days, today, today))
    assertEquals(7, rowIndexForDate(days, today, today.plusDays(6)))
  }

  @Test
  fun `a day with no row of its own goes to the nearest day that has one`() {
    // Nothing between -3 and 0, so the 22nd (offset -2) is closest to the -3 row.
    val days = listOf(-3, 0, 1, 2, 3, 4, 5, 6)

    assertEquals(0, rowIndexForDate(days, today, today.minusDays(2)))
    // Far in the future: the last row is the nearest thing there is.
    assertEquals(7, rowIndexForDate(days, today, today.plusMonths(3)))
    // Far in the past: the first row.
    assertEquals(0, rowIndexForDate(days, today, today.minusMonths(3)))
  }

  @Test
  fun `a tie lands on the earlier of the two rows`() {
    // Offset 2 is one day from both the 1 row and the 3 row.
    val days = listOf(1, 3)

    assertEquals(0, rowIndexForDate(days, today, today.plusDays(2)))
  }

  @Test
  fun `no rows at all is not a crash`() {
    assertNull(rowIndexForDate(emptyList(), today, today))
  }
}
