package fi.merilainen.treenivalmentaja

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The week list's day headings.
 *
 * Worth its own test because the version this replaced was wrong and looked right: it named rows
 * from their position — "Keskiviikko" for the third one — which is only correct in a week that
 * starts on a Monday. Opened on a Tuesday it called Thursday "Keskiviikko" and nobody noticed,
 * because nobody scrolls a seven-row list looking for a lie.
 */
class DayLabelTest {

  /** A Monday, so the old positional labels would have agreed here and disagreed everywhere else. */
  private val monday = LocalDate.of(2026, 8, 10)

  @Test
  fun `today, tomorrow and yesterday are named rather than dated`() {
    assertEquals("Tänään · 10.8.", dayLabel(monday, 0))
    assertEquals("Huomenna · 11.8.", dayLabel(monday, 1))
    assertEquals("Eilen · 9.8.", dayLabel(monday, -1))
  }

  @Test
  fun `every other day carries its real weekday and date`() {
    assertEquals("Keskiviikko 12.8.", dayLabel(monday, 2))
    assertEquals("Torstai 13.8.", dayLabel(monday, 3))
  }

  /** The failure the old code had: the same offset from a different day is a different weekday. */
  @Test
  fun `the weekday comes from the date, not from the row's position`() {
    val tuesday = LocalDate.of(2026, 8, 11)

    assertEquals("Torstai 13.8.", dayLabel(tuesday, 2))
  }

  @Test
  fun `a day far back is still labelled correctly`() {
    assertEquals("Maanantai 13.7.", dayLabel(monday, -28))
  }

  @Test
  fun `a day in another month keeps that month`() {
    assertEquals("Keskiviikko 2.9.", dayLabel(monday, 23))
  }
}
