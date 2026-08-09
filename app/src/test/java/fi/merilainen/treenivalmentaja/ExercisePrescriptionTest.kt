package fi.merilainen.treenivalmentaja

import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line under an exercise name, in the shorthand a gym log uses.
 *
 * Until now the screens printed the name and nothing else, so a session read "• Alasoutu" with no
 * hint of what to load or how many to do — everything the plan knew was thrown away at the point
 * it mattered most.
 */
class ExercisePrescriptionTest {

    @Test
    fun `an exercise the plan says nothing about prints nothing`() {
        assertEquals("", Exercise(name = "Alasoutu").prescription())
    }

    @Test
    fun `reps alone`() {
        assertEquals("10", Exercise(name = "Kissa-lehmä", reps = 10).prescription())
    }

    @Test
    fun `sets multiply the reps`() {
        assertEquals("3 × 12", Exercise(name = "Face pull", sets = 3, reps = 12).prescription())
    }

    @Test
    fun `weight is appended after the work`() {
        val text = Exercise(name = "Hauiskääntö", sets = 3, reps = 10, weightKg = 18.0).prescription()

        assertEquals("3 × 10 · 18 kg", text)
    }

    /** Finnish decimal comma, and no trailing zero on a whole number. */
    @Test
    fun `a fractional weight uses a comma`() {
        val text = Exercise(name = "Taljaojentaja", sets = 3, reps = 10, weightKg = 12.5).prescription()

        assertEquals("3 × 10 · 12,5 kg", text)
    }

    @Test
    fun `per side is marked`() {
        assertEquals("10 / puoli", Exercise(name = "Bird dog", reps = 10, perSide = true).prescription())
    }

    @Test
    fun `a hold reads in seconds`() {
        assertEquals("30 s / puoli", Exercise(name = "Venytys", durationSec = 30, perSide = true).prescription())
    }

    @Test
    fun `a rep range is shown as a range`() {
        assertEquals("6–8", Exercise(name = "Punnerrus", repsMin = 6, repsMax = 8).prescription())
    }

    /**
     * The case this was built for: a ramp, where a single weight cannot describe the exercise.
     * Every set is listed, because the progression is the instruction.
     */
    @Test
    fun `a ramp lists every set`() {
        val text = Exercise(
            name = "Alasoutu",
            setPlan = listOf(
                ExerciseSet(weightKg = 25.0, reps = 10),
                ExerciseSet(weightKg = 35.0, reps = 10),
                ExerciseSet(weightKg = 45.0, reps = 10),
                ExerciseSet(weightKg = 55.0, reps = 10),
            ),
        ).prescription()

        assertEquals("25 kg × 10 · 35 kg × 10 · 45 kg × 10 · 55 kg × 10", text)
    }

    /** Reps drop as the weight climbs — the reason a plain `sets × reps` cannot express this. */
    @Test
    fun `a ramp may change reps as well as weight`() {
        val text = Exercise(
            name = "Nautilus yhden jalan jalkaprässi",
            setPlan = listOf(
                ExerciseSet(weightKg = 36.0, reps = 15),
                ExerciseSet(weightKg = 55.0, reps = 12),
                ExerciseSet(weightKg = 73.0, reps = 10),
            ),
        ).prescription()

        assertEquals("36 kg × 15 · 55 kg × 12 · 73 kg × 10", text)
    }
}
