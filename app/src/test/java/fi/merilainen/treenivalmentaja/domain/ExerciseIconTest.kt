package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every movement name the shipped plans actually use, mapped.
 *
 * The names are copied from `sample-data/plan.json` and the seeded starter week rather than
 * invented, because a rule that matches a name nobody writes is not a rule.
 */
class ExerciseIconTest {
  @Test
  fun `the movements the plans actually contain all map to something deliberate`() {
    val expected = mapOf(
      "Lankku" to ExerciseIcon.PLANK,
      "Sivulankku" to ExerciseIcon.SIDE_PLANK,
      "Punnerrus" to ExerciseIcon.PUSHUP,
      "Kevyt punnerrus" to ExerciseIcon.PUSHUP,
      "Timanttipunnerrus" to ExerciseIcon.PUSHUP,
      "Kyykky" to ExerciseIcon.SQUAT,
      "Goblet-kyykky" to ExerciseIcon.SQUAT,
      "Bulgarialainen askelkyykky" to ExerciseIcon.LUNGE,
      "Kahvakuulaheilautus" to ExerciseIcon.SWING,
      "Käsipainosoutu" to ExerciseIcon.ROW,
      "Vatsarutistus" to ExerciseIcon.CRUNCH,
      "Vatsarutistus penkillä" to ExerciseIcon.CRUNCH,
      "Vinot vatsarutistukset" to ExerciseIcon.CRUNCH,
      "Kissanlehmä" to ExerciseIcon.QUADRUPED,
      "Bird dog" to ExerciseIcon.BIRD_DOG,
      "Lonkankoukistajan venytys" to ExerciseIcon.STRETCH,
    )

    expected.forEach { (name, icon) -> assertEquals(name, icon, ExerciseIcon.forName(name)) }
  }

  @Test
  fun `a qualifier wins over the family it belongs to`() {
    // Reversing either pair in the rule list would silently hand the special case the general
    // icon, and both names are in the shipped plans.
    assertEquals(ExerciseIcon.SIDE_PLANK, ExerciseIcon.forName("Sivulankku"))
    assertEquals(ExerciseIcon.PLANK, ExerciseIcon.forName("Lankku"))
    assertEquals(ExerciseIcon.BIRD_DOG, ExerciseIcon.forName("Bird dog"))
    assertEquals(ExerciseIcon.QUADRUPED, ExerciseIcon.forName("Kissanlehmä"))
    // "Bulgarialainen askelkyykky" contains "kyykky" too; the lunge rule must be read first.
    assertEquals(ExerciseIcon.LUNGE, ExerciseIcon.forName("Bulgarialainen askelkyykky"))
  }

  @Test
  fun `case and surrounding words do not matter`() {
    assertEquals(ExerciseIcon.PUSHUP, ExerciseIcon.forName("LEVEÄ PUNNERRUS"))
    assertEquals(ExerciseIcon.SQUAT, ExerciseIcon.forName("Kyykky tangolla, 3 x 5"))
  }

  @Test
  fun `an unrecognised movement gets the plain figure rather than a wrong guess`() {
    assertEquals(ExerciseIcon.GENERIC, ExerciseIcon.forName("Hyppynaru"))
    assertEquals(ExerciseIcon.GENERIC, ExerciseIcon.forName(""))
  }
}
