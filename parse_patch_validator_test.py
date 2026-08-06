import re

with open("app/src/test/java/fi/merilainen/treenivalmentaja/data/importer/PlanValidatorTest.kt", "r") as f:
    content = f.read()

# Add a test for missing time when timeIsFixed is true
test_missing_time = """
  @Test
  fun `session with timeIsFixed true but missing time is rejected`() {
    val errors =
      errorsOf(
        \"\"\"
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "timeIsFixed": true, "durationMin": 30 }
          ] } ]
        }
        \"\"\"
      )
    val message = errors.single { it.path == "weeks[0].sessions[0].time" }.message
    assertTrue(message.contains("kellonaika puuttuu, vaikka timeIsFixed on true"))
  }

  @Test
  fun `session without time is accepted if timeIsFixed is false`() {
    val plan = 
        \"\"\"
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "durationMin": 30 }
          ] } ]
        }
        \"\"\"
    val document = PlanJson.parse(plan).getOrThrow()
    val validated = PlanValidator.validate(document) as ValidationOutcome.Valid
    assertEquals(1, validated.plan.sessions.size)
    assertNull(validated.plan.sessions[0].scheduledTime)
    assertFalse(validated.plan.sessions[0].timeIsFixed)
  }
"""

content = re.sub(r'  @Test\n  fun `session without any work content is rejected`\(\) \{', test_missing_time + '\n  @Test\n  fun `session without any work content is rejected`() {', content)

with open("app/src/test/java/fi/merilainen/treenivalmentaja/data/importer/PlanValidatorTest.kt", "w") as f:
    f.write(content)
