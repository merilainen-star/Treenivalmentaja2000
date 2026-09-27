package fi.merilainen.treenivalmentaja.domain

import org.junit.Assert.*
import org.junit.Test

class RunProfileTest {
  @Test fun `relaxed accelerations explicitly not sprints stay accelerations`() {
    assertEquals(RunPhase.STRIDE, RunStep("Kiihdytys 1/3 - rento, ei sprinttiä", durationSec = 15).phase())
    assertEquals(RunPhase.RECOVERY, RunStep("Palautus - kävele kevyesti", durationSec = 90).phase())
    assertEquals(RunPhase.EASY, RunStep("Loppu - 10 min hyvin kevyttä juoksua", durationSec = 600).phase())
  }
  @Test fun `different targets and unknown stages are never merged`() {
    val steps = listOf(RunStep("Veto 1", distanceMeters = 400, paceSecPerKm = 260),
      RunStep("Veto 2", distanceMeters = 400, paceSecPerKm = 260),
      RunStep("Veto 3", distanceMeters = 400, paceSecPerKm = 250),
      RunStep("A", durationSec = 30), RunStep("B", durationSec = 30))
    val summary = steps.compactPrescription()
    assertEquals(4, summary.size)
    assertTrue(summary.first().contains("2 × 400 m"))
    assertTrue(summary[1].contains("4:10 /km"))
  }
}
