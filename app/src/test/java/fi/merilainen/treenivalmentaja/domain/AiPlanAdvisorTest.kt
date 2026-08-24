package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlanAdvisorTest {
  private val parser = AdvisorResponseParser()

  @Test
  fun `parses a fenced structured proposal without trusting markdown`() {
    val response =
      parser.parse(
        """```json
          {"status":"proposal","summary":"Siirrä pitkä lenkki", "operations":[
            {"type":"MOVE","sessionId":"run-1","newDate":"2026-08-30"},
            {"type":"LIGHTEN","sessionId":"gym-1"}
          ]}
        ```""".trimIndent()
      ).getOrThrow() as AdvisorResponse.Proposal

    assertEquals(2, response.value.operations.size)
    assertEquals(LocalDate.of(2026, 8, 30), (response.value.operations[0] as AdvisorOperation.Move).newDate)
  }

  @Test
  fun `clarification is not misread as a proposal`() {
    val response = parser.parse("""{"status":"clarification","question":"Sopiiko sunnuntai?"}""").getOrThrow()
    assertEquals(AdvisorResponse.Clarification("Sopiiko sunnuntai?"), response)
  }

  @Test
  fun `rejects duplicate operations for one session`() {
    val result =
      parser.parse(
        """{"status":"proposal","operations":[{"type":"MOVE","sessionId":"x","newDate":"2026-08-30"},{"type":"LIGHTEN","sessionId":"x"}]}"""
      )
    assertTrue(result.isFailure)
  }

  @Test
  fun `prompt names measured health context and forbids inferring missing values`() {
    val target = session("run-1")
    val prompt =
      AdvisorPromptBuilder().build(
        target = target,
        sessions = listOf(target),
        today = LocalDate.of(2026, 8, 24),
        constraints = "Ei tiistaisin",
        healthContext = "Oura 2026-08-24: readiness=82; sleep=76",
      )

    assertTrue(prompt.contains("readiness=82"))
    assertTrue(prompt.contains("puuttuvia arvoja ei saa päätellä"))
  }

  private fun session(id: String) =
    TrainingSession(
      id = id,
      planId = "p1",
      type = WorkoutType.RUNNING,
      weekNumber = 1,
      scheduledDate = "2026-08-30",
      scheduledTime = "10:00",
      remindAtUtc = 0,
    )
}
