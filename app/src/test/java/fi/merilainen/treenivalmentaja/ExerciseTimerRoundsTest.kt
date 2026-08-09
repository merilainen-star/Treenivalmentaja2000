package fi.merilainen.treenivalmentaja

import fi.merilainen.treenivalmentaja.domain.Exercise
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How many times an exercise's clock has to run, decided from the plan's own fields.
 *
 * The behaviour this replaces read the exercise's *name*: a movement was timed if the name
 * contained "lankku". So "lonkankoukistajan venytys 30 s/puoli" got no clock despite carrying
 * `durationSec`, and "sivulankku 20 s/puoli" got one clock when the exercise needs two — which
 * left the second side to be counted in your head while holding the first.
 */
class ExerciseTimerRoundsTest {

    private fun exercise(
        name: String,
        durationSec: Int? = null,
        perSide: Boolean? = null,
        sets: Int? = null,
    ) = Exercise(name = name, durationSec = durationSec, perSide = perSide, sets = sets)

    @Test
    fun `an exercise without a duration is not timed`() {
        assertEquals(emptyList<String>(), exercise("Kyykky", perSide = true).timedRounds())
    }

    @Test
    fun `a plain hold runs once and is not labelled`() {
        assertEquals(listOf(""), exercise("lankku 30 s", durationSec = 30).timedRounds())
    }

    /** The case that prompted this: one clock for each side, named so neither is lost. */
    @Test
    fun `a per-side hold runs once per side`() {
        val rounds = exercise("sivulankku 20 s/puoli", durationSec = 20, perSide = true)
            .timedRounds()

        assertEquals(listOf("Vasen", "Oikea"), rounds)
    }

    /** A stretch is timed for the same reason a plank is — the name has nothing to do with it. */
    @Test
    fun `a per-side stretch is timed like any other hold`() {
        val rounds = exercise("lonkankoukistajan venytys 30 s/puoli", durationSec = 30, perSide = true)
            .timedRounds()

        assertEquals(listOf("Vasen", "Oikea"), rounds)
    }

    @Test
    fun `sets repeat the clock and are numbered`() {
        val rounds = exercise("etunojapito", durationSec = 45, sets = 3).timedRounds()

        assertEquals(listOf("Sarja 1", "Sarja 2", "Sarja 3"), rounds)
    }

    /** Per side wins over sets rather than multiplying into something nobody wrote. */
    @Test
    fun `per side takes precedence over sets`() {
        val rounds = exercise("sivulankku", durationSec = 20, perSide = true, sets = 3)
            .timedRounds()

        assertEquals(listOf("Vasen", "Oikea"), rounds)
    }
}
