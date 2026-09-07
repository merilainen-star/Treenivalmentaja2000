package fi.merilainen.treenivalmentaja.data.importer

import fi.merilainen.treenivalmentaja.domain.NextProgramRequirements
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedPlanValidationTest {
  private val start = LocalDate.of(2026, 9, 14)
  private val required = NextProgramRequirements(start, "Europe/Helsinki", 8)

  private fun json(weeks: Int = 8, zone: String = "Europe/Helsinki", date: LocalDate = start,
                   sessionDate: (Int) -> LocalDate = { start.plusWeeks(it - 1L) }): String = """
    {"schemaVersion":1,"plan":{"id":"next","name":"Next","startDate":"$date","timeZone":"$zone"},
    "weeks":[${(1..weeks).joinToString(",") { week -> """
      {"weekNumber":$week,"sessions":[{"id":"s$week","type":"RUNNING","date":"${sessionDate(week)}","time":"17:00","durationMin":30}]}
    """ }}]}
  """

  private fun validate(json: String) = PlanValidator.validate(PlanJson.parse(json).getOrThrow(), required)

  @Test fun acceptsExactlyTheRequestedBlock() { assertTrue(validate(json()) is ValidationOutcome.Valid) }
  @Test fun rejectsAValidButShorterProgramme() { assertTrue(validate(json(weeks = 7)) is ValidationOutcome.Errors) }
  @Test fun rejectsADifferentValidZone() { assertTrue(validate(json(zone = "UTC")) is ValidationOutcome.Errors) }
  @Test fun rejectsADifferentStartDate() { assertTrue(validate(json(date = start.minusDays(1))) is ValidationOutcome.Errors) }
  @Test fun rejectsSessionsBeyondTheRequestedEnd() {
    assertTrue(validate(json(sessionDate = { start.plusWeeks(it.toLong()) })) is ValidationOutcome.Errors)
  }
  @Test fun rejectsDatesAssignedToTheWrongWeek() {
    assertTrue(validate(json(sessionDate = { start })) is ValidationOutcome.Errors)
  }
  @Test fun ordinaryImportsStillAcceptOtherLengthsAndZones() {
    assertTrue(PlanValidator.validate(PlanJson.parse(json(weeks = 3, zone = "UTC")).getOrThrow()) is ValidationOutcome.Valid)
  }
}
