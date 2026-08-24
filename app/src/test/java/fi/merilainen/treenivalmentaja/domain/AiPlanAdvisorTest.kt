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

  @Test
  fun `an explicit no_change status is a successful answer`() {
    val response =
      parser
        .parse("""{"status":"no_change","summary":"Kuormitus on sopiva, jatka suunnitelman mukaan."}""")
        .getOrThrow()

    assertEquals(
      AdvisorResponse.NoChange("Kuormitus on sopiva, jatka suunnitelman mukaan."),
      response,
    )
  }

  @Test
  fun `a proposal with no operations reads as no change, not as a broken answer`() {
    val response =
      parser
        .parse("""{"status":"proposal","summary":"Suunnitelma on kunnossa.","operations":[]}""")
        .getOrThrow()

    assertEquals(AdvisorResponse.NoChange("Suunnitelma on kunnossa."), response)
  }

  @Test
  fun `a proposal that omits the operations key entirely is also no change`() {
    val response =
      parser.parse("""{"status":"proposal","summary":"Ei muutettavaa."}""").getOrThrow()

    assertEquals(AdvisorResponse.NoChange("Ei muutettavaa."), response)
  }

  @Test
  fun `no change falls back to a sentence of its own when the model gives no summary`() {
    val response = parser.parse("""{"status":"no_change"}""").getOrThrow()

    assertEquals(AdvisorResponse.NoChange("AI ei ehdota muutoksia."), response)
  }

  @Test
  fun `an unknown status is still refused`() {
    assertTrue(parser.parse("""{"status":"siirrä kaikki"}""").isFailure)
  }

  @Test
  fun `the prompt offers no change as an option the model is allowed to take`() {
    val prompt =
      AdvisorPromptBuilder()
        .build(
          target = session("run-1"),
          sessions = listOf(session("run-1")),
          today = LocalDate.of(2026, 8, 24),
          constraints = "",
        )

    assertTrue(prompt.contains("no_change"))
    assertTrue(prompt.contains("älä keksi muutosta"))
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
