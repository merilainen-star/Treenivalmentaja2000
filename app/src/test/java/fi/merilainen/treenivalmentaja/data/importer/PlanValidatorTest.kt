package fi.merilainen.treenivalmentaja.data.importer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

import fi.merilainen.treenivalmentaja.domain.Intensity
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers `docs/PLAN_SCHEMA.md`: a valid document, broken documents, and duplicate ids. */
class PlanValidatorTest {

  private fun validate(json: String): ValidationOutcome {
    val document = PlanJson.parse(json).getOrNull()
    assertNotNull("expected the fixture to be parseable JSON", document)
    return PlanValidator.validate(document!!)
  }

  private fun errorsOf(json: String): List<ImportError> =
    (validate(json) as ValidationOutcome.Errors).errors

  private fun pathsOf(json: String): List<String> = errorsOf(json).map { it.path }

  // ------------------------------------------------------------------ valid

  @Test
  fun `valid document produces sessions with resolved absolute times`() {
    val outcome = validate(VALID_PLAN)
    val plan = (outcome as ValidationOutcome.Valid).plan

    assertEquals("plan-testi", plan.id)
    assertEquals("Testisuunnitelma", plan.name)
    assertEquals(1, plan.schemaVersion)
    assertEquals("Europe/Helsinki", plan.timeZone)
    assertEquals("2026-08-10", plan.startDate)
    assertEquals(2, plan.sessions.size)

    val strength = plan.sessions.first()
    assertEquals("s-1", strength.id)
    assertEquals(WorkoutType.STRENGTH, strength.type)
    assertEquals("2026-08-10", strength.scheduledDate)
    assertEquals("07:00", strength.scheduledTime)
    assertEquals(45, strength.durationMin)
    assertEquals(Intensity.EASY, strength.intensity)
    assertEquals(3, strength.rounds)
    assertEquals(SessionStatus.PLANNED, strength.status)
    assertEquals(2, strength.exercises?.size)
    assertEquals("Kyykky", strength.exercises?.first()?.name)
    assertEquals(60.0, strength.exercises?.first()?.weightKg!!, 0.0001)

    // 2026-08-10 07:00 in Europe/Helsinki is 04:00 UTC (EEST, UTC+3).
    assertEquals(1786334400000L, strength.remindAtUtc)

    val run = plan.sessions[1]
    assertEquals(WorkoutType.RUNNING, run.type)
    assertEquals(5.0, run.distanceKm!!, 0.0001)
    assertNull("a running session needs no exercise list", run.exercises)
    assertEquals(25, run.lighterAlternative?.durationMin)
  }

  @Test
  fun `minimal document is accepted`() {
    val outcome =
      validate(
        """
        {
          "schemaVersion": 1,
          "plan": {
            "id": "plan-minimal",
            "name": "Yksi viikko",
            "timeZone": "Europe/Helsinki",
            "startDate": "2026-08-10"
          },
          "weeks": [
            {
              "weekNumber": 1,
              "sessions": [
                { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "16:30", "durationMin": 45 }
              ]
            }
          ]
        }
        """
      )
    assertTrue(outcome is ValidationOutcome.Valid)
  }

  // ------------------------------------------------------------------ broken

  @Test
  fun `unparseable text is reported as unreadable rather than thrown`() {
    val result = PlanJson.parse("{ this is not json")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("JSON"))
  }

  @Test
  fun `missing required fields are all reported at once`() {
    val paths =
      pathsOf(
        """
        {
          "plan": { "name": "" },
          "weeks": []
        }
        """
      )
    assertTrue("schemaVersion" in paths)
    assertTrue("plan.id" in paths)
    assertTrue("plan.name" in paths)
    assertTrue("plan.timeZone" in paths)
    assertTrue("plan.startDate" in paths)
    assertTrue("weeks" in paths)
  }

  @Test
  fun `broken session fields are reported with a path and a finnish message`() {
    val errors = errorsOf(BROKEN_PLAN)
    val byPath = errors.associate { it.path to it.message }

    assertTrue(byPath.containsKey("weeks[0].sessions[0].time"))
    assertTrue(byPath["weeks[0].sessions[0].time"]!!.contains("25:00"))
    assertTrue(byPath["weeks[0].sessions[0].time"]!!.contains("HH:mm"))

    assertTrue(byPath.containsKey("weeks[0].sessions[1].type"))
    assertTrue(byPath["weeks[0].sessions[1].type"]!!.contains("SWIMMING"))

    assertTrue(byPath.containsKey("weeks[0].sessions[2].date"))
    assertTrue(byPath.containsKey("plan.timeZone"))
    assertTrue(byPath["plan.timeZone"]!!.contains("Europe/Helsinky"))
  }


  @Test
  fun `session with timeIsFixed true but missing time is rejected`() {
    val errors =
      errorsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "timeIsFixed": true, "durationMin": 30 }
          ] } ]
        }
        """
      )
    val message = errors.single { it.path == "weeks[0].sessions[0].time" }.message
    assertTrue(message.contains("kellonaika puuttuu, vaikka timeIsFixed on true"))
  }

  @Test
  fun `session without time is accepted if timeIsFixed is false`() {
    val plan = 
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "durationMin": 30 }
          ] } ]
        }
        """
    val document = PlanJson.parse(plan).getOrThrow()
    val validated = PlanValidator.validate(document) as ValidationOutcome.Valid
    assertEquals(1, validated.plan.sessions.size)
    assertNull(validated.plan.sessions[0].scheduledTime)
    assertFalse(validated.plan.sessions[0].timeIsFixed)
  }

  @Test
  fun `session without any work content is rejected`() {
    val paths =
      pathsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "07:00" }
          ] } ]
        }
        """
      )
    assertTrue("weeks[0].sessions[0]" in paths)
  }

  @Test
  fun `session before the plan start date is rejected`() {
    val errors =
      errorsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-09", "time": "07:00", "durationMin": 30 }
          ] } ]
        }
        """
      )
    val message = errors.single { it.path == "weeks[0].sessions[0].date" }.message
    assertTrue(message.contains("2026-08-10"))
  }

  @Test
  fun `unsupported schema version is rejected`() {
    val errors = errorsOf(VALID_PLAN.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
    val message = errors.single { it.path == "schemaVersion" }.message
    assertTrue(message.contains("2"))
  }

  @Test
  fun `non-positive numbers are rejected`() {
    val paths =
      pathsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "07:00",
              "durationMin": 0, "distanceKm": -5.0, "rounds": 0 }
          ] } ]
        }
        """
      )
    assertTrue("weeks[0].sessions[0].durationMin" in paths)
    assertTrue("weeks[0].sessions[0].distanceKm" in paths)
    assertTrue("weeks[0].sessions[0].rounds" in paths)
  }

  @Test
  fun `exercise needs a name and either reps or duration`() {
    val paths =
      pathsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [ { "weekNumber": 1, "sessions": [
            { "id": "s-1", "type": "STRENGTH", "date": "2026-08-10", "time": "07:00",
              "exercises": [ { "sets": 3 } ] }
          ] } ]
        }
        """
      )
    assertTrue("weeks[0].sessions[0].exercises[0].name" in paths)
    assertTrue("weeks[0].sessions[0].exercises[0]" in paths)
  }

  // ------------------------------------------------------------------ duplicates

  @Test
  fun `duplicate session id points at the first occurrence`() {
    val errors = errorsOf(DUPLICATE_ID_PLAN)
    val duplicate = errors.single { it.path == "weeks[0].sessions[1].id" }
    assertTrue(duplicate.message.contains("s-1"))
    assertTrue(duplicate.message.contains("weeks[0].sessions[0]"))
  }

  @Test
  fun `duplicate session id across different weeks is still a duplicate`() {
    val errors =
      errorsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [
            { "weekNumber": 1, "sessions": [
              { "id": "sama", "type": "RUNNING", "date": "2026-08-10", "time": "07:00", "durationMin": 30 } ] },
            { "weekNumber": 2, "sessions": [
              { "id": "sama", "type": "RUNNING", "date": "2026-08-17", "time": "07:00", "durationMin": 30 } ] }
          ]
        }
        """
      )
    val duplicate = errors.single { it.path == "weeks[1].sessions[0].id" }
    assertTrue(duplicate.message.contains("weeks[0].sessions[0]"))
  }

  @Test
  fun `duplicate week number is rejected`() {
    val errors =
      errorsOf(
        """
        {
          "schemaVersion": 1,
          "plan": { "id": "p", "name": "n", "timeZone": "Europe/Helsinki", "startDate": "2026-08-10" },
          "weeks": [
            { "weekNumber": 1, "sessions": [
              { "id": "a", "type": "RUNNING", "date": "2026-08-10", "time": "07:00", "durationMin": 30 } ] },
            { "weekNumber": 1, "sessions": [
              { "id": "b", "type": "RUNNING", "date": "2026-08-17", "time": "07:00", "durationMin": 30 } ] }
          ]
        }
        """
      )
    val duplicate = errors.single { it.path == "weeks[1].weekNumber" }
    assertTrue(duplicate.message.contains("weeks[0]"))
  }

  // ------------------------------------------------------------------ prose as exercises

  /**
   * Regression: `tools/parse_ics2.py` split a running session's description on its commas and
   * emitted the sentence fragments as exercises. "Pidä vauhti sellaisena, että pystyt puhumaan."
   * became two exercises with a name and nothing else, 16 of them across an eight-week plan, and
   * the whole import failed. An exercise carrying only a name is prose, and must be rejected.
   */
  @Test
  fun `exercise with neither reps nor duration is rejected`() {
    val paths =
      pathsOf(
        """
        {
          "schemaVersion": 1,
          "plan": {
            "id": "plan-testi", "name": "Testi", "timeZone": "Europe/Helsinki",
            "startDate": "2026-08-10"
          },
          "weeks": [
            {
              "weekNumber": 1,
              "sessions": [
                {
                  "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "18:00",
                  "durationMin": 42,
                  "description": "Pitkä lenkki. Pidä vauhti sellaisena, että pystyt puhumaan.",
                  "exercises": [
                    { "name": "Pidä vauhti sellaisena" },
                    { "name": "että pystyt puhumaan" },
                    { "name": "Lankku", "durationSec": 30 }
                  ]
                }
              ]
            }
          ]
        }
        """
      )
    assertEquals(
      listOf("weeks[0].sessions[0].exercises[0]", "weeks[0].sessions[0].exercises[1]"),
      paths,
    )
  }

  /** The session itself stays valid on `durationMin` alone once the prose is gone. */
  @Test
  fun `running session with no exercises at all is valid`() {
    val outcome =
      validate(
        """
        {
          "schemaVersion": 1,
          "plan": {
            "id": "plan-testi", "name": "Testi", "timeZone": "Europe/Helsinki",
            "startDate": "2026-08-10"
          },
          "weeks": [
            {
              "weekNumber": 1,
              "sessions": [
                {
                  "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "18:00",
                  "durationMin": 42, "description": "Pitkä rauhallinen lenkki."
                }
              ]
            }
          ]
        }
        """
      )
    val plan = (outcome as ValidationOutcome.Valid).plan
    assertNull(plan.sessions.single().exercises)
  }

  // ------------------------------------------------------------------ content hash

  @Test
  fun `content hash ignores formatting but not content`() {
    val reformatted = VALID_PLAN.replace("\n", "\n   ").replace("  ", " ")
    assertEquals(PlanJson.contentHash(VALID_PLAN), PlanJson.contentHash(reformatted))

    val edited = VALID_PLAN.replace("\"durationMin\": 45", "\"durationMin\": 50")
    assertTrue(PlanJson.contentHash(VALID_PLAN) != PlanJson.contentHash(edited))
  }

  private companion object {
    const val VALID_PLAN =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-testi",
          "name": "Testisuunnitelma",
          "timeZone": "Europe/Helsinki",
          "startDate": "2026-08-10",
          "description": "Kahden harjoituksen testiviikko."
        },
        "weeks": [
          {
            "weekNumber": 1,
            "focus": "Peruskestävyys",
            "sessions": [
              {
                "id": "s-1",
                "type": "STRENGTH",
                "date": "2026-08-10",
                "time": "07:00",
                "durationMin": 45,
                "intensity": "EASY",
                "rounds": 3,
                "description": "Aamun keskivartalo.",
                "exercises": [
                  { "name": "Kyykky", "sets": 3, "reps": 10, "weightKg": 60.0, "restSec": 90 },
                  { "name": "Lankku", "sets": 3, "durationSec": 45, "notes": "Kädet suorina." }
                ]
              },
              {
                "id": "s-2",
                "type": "RUNNING",
                "date": "2026-08-10",
                "time": "16:30",
                "durationMin": 45,
                "distanceKm": 5.0,
                "intensity": "MODERATE",
                "lighterAlternative": { "durationMin": 25, "intensity": "EASY" }
              }
            ]
          }
        ]
      }
      """

    const val BROKEN_PLAN =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-rikki",
          "name": "Rikkinäinen",
          "timeZone": "Europe/Helsinky",
          "startDate": "2026-08-10"
        },
        "weeks": [
          {
            "weekNumber": 1,
            "sessions": [
              { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "25:00", "durationMin": 30 },
              { "id": "s-2", "type": "SWIMMING", "date": "2026-08-11", "time": "07:00", "durationMin": 30 },
              { "id": "s-3", "type": "RUNNING", "date": "10.8.2026", "time": "07:00", "durationMin": 30 }
            ]
          }
        ]
      }
      """

    const val DUPLICATE_ID_PLAN =
      """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "plan-duplikaatti",
          "name": "Duplikaatti",
          "timeZone": "Europe/Helsinki",
          "startDate": "2026-08-10"
        },
        "weeks": [
          {
            "weekNumber": 1,
            "sessions": [
              { "id": "s-1", "type": "RUNNING", "date": "2026-08-10", "time": "07:00", "durationMin": 30 },
              { "id": "s-1", "type": "SKIING",  "date": "2026-08-11", "time": "08:00", "durationMin": 60 }
            ]
          }
        ]
      }
      """
  }
}
