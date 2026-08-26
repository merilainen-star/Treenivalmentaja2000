package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSummaryTest {
  @Test
  fun `a gym session where every lift agrees gets its sets and reps`() {
    val session = listOf(
      Exercise("Kyykky", sets = 3, reps = 12),
      Exercise("Penkkipunnerrus", sets = 3, reps = 12),
      Exercise("Pystypunnerrus", sets = 3, reps = 12),
    )

    assertEquals("3 × 12", uniformSetsAndReps(session))
  }

  @Test
  fun `a circuit whose movements differ gets nothing rather than a made-up number`() {
    // The real starter week: five movements, five different prescriptions, one of them a hold.
    val circuit = listOf(
      Exercise("Bulgarialainen askelkyykky", reps = 8, perSide = true),
      Exercise("Kahvakuulaheilautus", reps = 15),
      Exercise("Timanttipunnerrus", repsMin = 6, repsMax = 8),
      Exercise("Sivulankku", durationSec = 20, perSide = true),
    )

    assertNull(uniformSetsAndReps(circuit))
  }

  @Test
  fun `one lift out of step is enough to withhold the line`() {
    val almost = listOf(
      Exercise("Kyykky", sets = 3, reps = 12),
      Exercise("Maastaveto", sets = 3, reps = 8),
    )

    assertNull(uniformSetsAndReps(almost))
  }

  @Test
  fun `per side is part of the agreement and part of the answer`() {
    assertEquals(
      "3 × 10 / puoli",
      uniformSetsAndReps(
        listOf(
          Exercise("Askelkyykky", sets = 3, reps = 10, perSide = true),
          Exercise("Yhden jalan nosto", sets = 3, reps = 10, perSide = true),
        )
      ),
    )
    assertNull(
      uniformSetsAndReps(
        listOf(
          Exercise("Askelkyykky", sets = 3, reps = 10, perSide = true),
          Exercise("Kyykky", sets = 3, reps = 10),
        )
      )
    )
  }

  @Test
  fun `a session that names no sets has no sets line`() {
    assertNull(uniformSetsAndReps(listOf(Exercise("Lankku", durationSec = 30))))
    assertNull(uniformSetsAndReps(emptyList()))
  }
}
