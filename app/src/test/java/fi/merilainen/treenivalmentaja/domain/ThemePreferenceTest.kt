package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a stored theme preference is allowed to do to the app.
 *
 * The reading half matters more than it looks: this value is read before the first frame is drawn,
 * so anything it does wrong it does to the whole app rather than to one screen.
 */
class ThemePreferenceTest {

  @Test
  fun `a stored constant name reads back as itself`() {
    ThemePreference.entries.forEach { preference ->
      assertEquals(preference, ThemePreference.fromStored(preference.name))
    }
  }

  @Test
  fun `nothing stored means the system decides`() {
    assertEquals(ThemePreference.SYSTEM, ThemePreference.fromStored(null))
    assertEquals(ThemePreference.SYSTEM, ThemePreference.DEFAULT)
  }

  /**
   * An option dropped from the enum, or a preference written by a build that had one this build
   * does not, must not stop Settings from drawing. It hands the decision back to the system.
   */
  @Test
  fun `an unrecognised value falls back rather than throwing`() {
    assertEquals(ThemePreference.DEFAULT, ThemePreference.fromStored("SEPIA"))
    assertEquals(ThemePreference.DEFAULT, ThemePreference.fromStored(""))
    assertEquals(ThemePreference.DEFAULT, ThemePreference.fromStored("light"))
  }

  /** Every option is offered by name in Settings, so none of them may be blank or repeated. */
  @Test
  fun `every option has a distinct label`() {
    val labels = ThemePreference.entries.map { it.label }
    assertEquals(labels.size, labels.toSet().size)
    labels.forEach { assertEquals(it, it.trim()) }
    assertEquals(emptyList<String>(), labels.filter { it.isBlank() })
  }
}
