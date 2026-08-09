package fi.merilainen.treenivalmentaja.data.importer

import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.ExerciseSet
import fi.merilainen.treenivalmentaja.domain.Intensity
import fi.merilainen.treenivalmentaja.domain.LighterAlternative
import fi.merilainen.treenivalmentaja.domain.SessionStatus
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import fi.merilainen.treenivalmentaja.domain.WorkoutType
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** A document that passed validation, ready to be written to Room. */
data class ValidatedPlan(
  val id: String,
  val name: String,
  val schemaVersion: Int,
  val timeZone: String,
  val startDate: String,
  val description: String?,
  val sessions: List<TrainingSession>,
)

sealed interface ValidationOutcome {
  data class Valid(val plan: ValidatedPlan) : ValidationOutcome

  data class Errors(val errors: List<ImportError>) : ValidationOutcome
}

/**
 * Validates a parsed [PlanDocumentDto] against the Treenivalmentaja Training Plan Schema v1.
 *
 * Collects **all** problems rather than failing on the first one, so a user fixing a hand-written
 * plan sees the whole list at once. Nothing may be written to Room unless this returns
 * [ValidationOutcome.Valid].
 */
object PlanValidator {

  const val SUPPORTED_SCHEMA_VERSION = 1

  private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

  fun validate(document: PlanDocumentDto): ValidationOutcome {
    val errors = mutableListOf<ImportError>()

    when (document.schemaVersion) {
      null -> errors += ImportError("schemaVersion", "pakollinen kenttä puuttuu")
      SUPPORTED_SCHEMA_VERSION -> Unit
      else ->
        errors +=
          ImportError(
            "schemaVersion",
            "tuntematon versio ${document.schemaVersion}, tuettu versio on " +
              "$SUPPORTED_SCHEMA_VERSION",
          )
    }

    val meta = document.plan
    if (meta == null) {
      errors += ImportError("plan", "pakollinen kohde puuttuu")
    }

    val planId = meta?.id?.takeIf { it.isNotBlank() }
    if (meta != null && planId == null) {
      errors += ImportError("plan.id", "pakollinen kenttä puuttuu tai on tyhjä")
    }
    val planName = meta?.name?.takeIf { it.isNotBlank() }
    if (meta != null && planName == null) {
      errors += ImportError("plan.name", "pakollinen kenttä puuttuu tai on tyhjä")
    }

    // Only inspect the sub-fields when `plan` exists — otherwise one missing object would
    // produce four near-identical errors.
    val zone = meta?.let { resolveZone(it.timeZone, errors) }
    val startDate = meta?.let { resolveDate(it.startDate, "plan.startDate", errors) }

    val weeks = document.weeks
    if (weeks == null) {
      errors += ImportError("weeks", "pakollinen lista puuttuu")
    } else if (weeks.isEmpty()) {
      errors += ImportError("weeks", "suunnitelmassa on oltava vähintään yksi viikko")
    }

    val sessions = mutableListOf<TrainingSession>()
    // id -> the path where it was first seen, so a duplicate can point at the original.
    val seenSessionIds = mutableMapOf<String, String>()
    val seenWeekNumbers = mutableMapOf<Int, String>()

    weeks?.forEachIndexed { weekIndex, week ->
      val weekPath = "weeks[$weekIndex]"
      if (week == null) {
        errors += ImportError(weekPath, "viikko on tyhjä (null)")
        return@forEachIndexed
      }

      val weekNumber = week.weekNumber
      when {
        weekNumber == null -> errors += ImportError("$weekPath.weekNumber", "pakollinen kenttä puuttuu")
        weekNumber < 1 ->
          errors += ImportError("$weekPath.weekNumber", "viikkonumeron on oltava vähintään 1")
        else -> {
          val first = seenWeekNumbers.put(weekNumber, weekPath)
          if (first != null) {
            errors +=
              ImportError(
                "$weekPath.weekNumber",
                "sama viikkonumero $weekNumber esiintyy jo kohdassa $first",
              )
          }
        }
      }

      val weekSessions = week.sessions
      if (weekSessions == null) {
        errors += ImportError("$weekPath.sessions", "pakollinen lista puuttuu")
        return@forEachIndexed
      }

      weekSessions.forEachIndexed { sessionIndex, session ->
        val path = "$weekPath.sessions[$sessionIndex]"
        if (session == null) {
          errors += ImportError(path, "harjoitus on tyhjä (null)")
          return@forEachIndexed
        }
        val validated =
          validateSession(
            session = session,
            path = path,
            planId = planId,
            weekNumber = weekNumber,
            zone = zone,
            planStartDate = startDate,
            seenSessionIds = seenSessionIds,
            errors = errors,
          )
        if (validated != null) sessions += validated
      }
    }

    if (errors.isNotEmpty()) return ValidationOutcome.Errors(errors)

    return ValidationOutcome.Valid(
      ValidatedPlan(
        id = planId!!,
        name = planName!!,
        schemaVersion = document.schemaVersion!!,
        timeZone = zone!!.id,
        startDate = startDate!!.toString(),
        description = meta?.description,
        sessions = sessions,
      )
    )
  }

  private fun validateSession(
    session: SessionDto,
    path: String,
    planId: String?,
    weekNumber: Int?,
    zone: ZoneId?,
    planStartDate: LocalDate?,
    seenSessionIds: MutableMap<String, String>,
    errors: MutableList<ImportError>,
  ): TrainingSession? {
    var usable = true

    val id = session.id?.takeIf { it.isNotBlank() }
    if (id == null) {
      errors += ImportError("$path.id", "pakollinen kenttä puuttuu tai on tyhjä")
      usable = false
    } else {
      val first = seenSessionIds.put(id, path)
      if (first != null) {
        errors +=
          ImportError("$path.id", "sama tunniste \"$id\" esiintyy jo kohdassa $first")
        usable = false
      }
    }

    val type = enumOrNull<WorkoutType>(session.type)
    if (type == null) {
      errors +=
        ImportError(
          "$path.type",
          describeEnumProblem(session.type, WorkoutType.entries.map { it.name }),
        )
      usable = false
    }

    val date = resolveDate(session.date, "$path.date", errors)
    if (date == null) {
      usable = false
    } else if (planStartDate != null && date.isBefore(planStartDate)) {
      errors +=
        ImportError(
          "$path.date",
          "päivä $date on ennen suunnitelman alkupäivää $planStartDate",
        )
      usable = false
    }

    val timeIsFixed = session.timeIsFixed ?: false
    var time: java.time.LocalTime? = null
    if (session.time != null) {
      time = resolveTime(session.time, "$path.time", errors)
      if (time == null) usable = false
    } else if (timeIsFixed) {
      errors += ImportError("$path.time", "kellonaika puuttuu, vaikka timeIsFixed on true")
      usable = false
    }

    val intensity =
      session.intensity?.let { raw ->
        enumOrNull<Intensity>(raw).also {
          if (it == null) {
            errors +=
              ImportError(
                "$path.intensity",
                describeEnumProblem(raw, Intensity.entries.map { e -> e.name }),
              )
            usable = false
          }
        }
      }

    if (!positiveOrNull(session.durationMin, "$path.durationMin", errors)) usable = false
    if (!positiveOrNull(session.distanceKm, "$path.distanceKm", errors)) usable = false
    if (!positiveOrNull(session.rounds, "$path.rounds", errors)) usable = false

    val exercises = validateExercises(session.exercises, "$path.exercises", errors)
    if (exercises == null) usable = false

    val hasWork =
      session.durationMin != null || session.distanceKm != null || !exercises.isNullOrEmpty()
    if (!hasWork) {
      errors +=
        ImportError(
          path,
          "harjoituksessa on oltava vähintään yksi seuraavista: durationMin, distanceKm tai " +
            "exercises",
        )
      usable = false
    }

    val lighter = validateLighter(session.lighterAlternative, "$path.lighterAlternative", errors)
    if (lighter == null && session.lighterAlternative != null) usable = false

    if (!usable || id == null || type == null || date == null || zone == null) {
      return null
    }
    
    // PlanValidator ei näe käyttäjän asetuksia, joten ajaton sessio saa väliaikaisen
    // 18:00-arvon. RescheduleAlarmsUseCase laskee oikean muistutusajan heti tuonnin jälkeen.
    val resolvedTime = time ?: LocalTime.of(18, 0)

    return TrainingSession(
      id = id,
      planId = planId.orEmpty(),
      type = type,
      weekNumber = weekNumber ?: 1,
      scheduledDate = date.toString(),
      scheduledTime = time?.format(TIME_FORMAT),
      remindAtUtc = ZonedDateTime.of(date, resolvedTime, zone).toInstant().toEpochMilli(),
      timeIsFixed = timeIsFixed,
      reminderOverride = null,
      durationMin = session.durationMin,
      distanceKm = session.distanceKm,
      intensity = intensity,
      rounds = session.rounds,
      roundsMin = session.roundsMin,
      roundsMax = session.roundsMax,
      targetPace = session.targetPace,
      warmupSec = session.warmupSec,
      exercises = exercises?.takeIf { it.isNotEmpty() },
      lighterAlternative = lighter,
      description = session.description,
      status = SessionStatus.PLANNED,
    )
  }

  /** Returns `null` when at least one exercise was invalid; an empty list when there were none. */
  private fun validateExercises(
    dtos: List<ExerciseDto?>?,
    path: String,
    errors: MutableList<ImportError>,
  ): List<Exercise>? {
    if (dtos == null) return emptyList()
    var ok = true
    val result = mutableListOf<Exercise>()
    dtos.forEachIndexed { index, dto ->
      val itemPath = "$path[$index]"
      if (dto == null) {
        errors += ImportError(itemPath, "liike on tyhjä (null)")
        ok = false
        return@forEachIndexed
      }
      val name = dto.name?.takeIf { it.isNotBlank() }
      if (name == null) {
        errors += ImportError("$itemPath.name", "pakollinen kenttä puuttuu tai on tyhjä")
        ok = false
      }
      if (!positiveOrNull(dto.sets, "$itemPath.sets", errors)) ok = false
      if (!positiveOrNull(dto.reps, "$itemPath.reps", errors)) ok = false
      if (!positiveOrNull(dto.durationSec, "$itemPath.durationSec", errors)) ok = false
      if (!nonNegativeOrNull(dto.weightKg, "$itemPath.weightKg", errors)) ok = false
      if (!nonNegativeOrNull(dto.restSec, "$itemPath.restSec", errors)) ok = false

      val setPlan = validateSetPlan(dto.setPlan, "$itemPath.setPlan", errors)
      if (setPlan == null) ok = false
      // Whether the writer *wrote* a setPlan, not whether it survived validation. A broken set
      // should be reported once, as the broken set — not also as "this exercise has no reps",
      // which would point at a field they deliberately left out.
      val hasSetPlan = dto.setPlan != null

      // Two descriptions of the same sets could disagree, and there would be no way to tell
      // which the writer meant, so carrying both is an error rather than a precedence rule.
      if (hasSetPlan && (dto.sets != null || dto.reps != null || dto.weightKg != null)) {
        errors +=
          ImportError(
            itemPath,
            "setPlan kuvaa jo sarjat — jätä sets, reps ja weightKg pois",
          )
        ok = false
      }
      if (!hasSetPlan && dto.reps == null && dto.durationSec == null) {
        errors += ImportError(itemPath, "liikkeellä on oltava joko reps tai durationSec")
        ok = false
      }

      if (name != null) {
        result +=
          Exercise(
            name = name,
            sets = dto.sets,
            reps = dto.reps,
            repsMin = dto.repsMin,
            repsMax = dto.repsMax,
            perSide = dto.perSide,
            weightKg = dto.weightKg,
            durationSec = dto.durationSec,
            restSec = dto.restSec,
            notes = dto.notes,
            setPlan = setPlan?.takeIf { it.isNotEmpty() },
          )
      }
    }
    return if (ok) result else null
  }

  /**
   * Returns `null` when a set was invalid, and an empty list when there was no `setPlan` at all —
   * the same convention [validateExercises] uses, so "absent" and "broken" never look alike.
   */
  private fun validateSetPlan(
    dtos: List<ExerciseSetDto?>?,
    path: String,
    errors: MutableList<ImportError>,
  ): List<ExerciseSet>? {
    if (dtos == null) return emptyList()
    if (dtos.isEmpty()) {
      errors += ImportError(path, "sarjalista on tyhjä — jätä kenttä kokonaan pois")
      return null
    }
    var ok = true
    val result = mutableListOf<ExerciseSet>()
    dtos.forEachIndexed { index, dto ->
      val itemPath = "$path[$index]"
      if (dto == null) {
        errors += ImportError(itemPath, "sarja on tyhjä (null)")
        ok = false
        return@forEachIndexed
      }
      if (!positiveOrNull(dto.reps, "$itemPath.reps", errors)) ok = false
      if (!positiveOrNull(dto.durationSec, "$itemPath.durationSec", errors)) ok = false
      if (!nonNegativeOrNull(dto.weightKg, "$itemPath.weightKg", errors)) ok = false
      if (dto.reps == null && dto.durationSec == null) {
        errors += ImportError(itemPath, "sarjalla on oltava joko reps tai durationSec")
        ok = false
      }
      result += ExerciseSet(weightKg = dto.weightKg, reps = dto.reps, durationSec = dto.durationSec)
    }
    return if (ok) result else null
  }

  private fun validateLighter(
    dto: LighterAlternativeDto?,
    path: String,
    errors: MutableList<ImportError>,
  ): LighterAlternative? {
    if (dto == null) return null
    var ok = true

    val intensity =
      dto.intensity?.let { raw ->
        enumOrNull<Intensity>(raw).also {
          if (it == null) {
            errors +=
              ImportError(
                "$path.intensity",
                describeEnumProblem(raw, Intensity.entries.map { e -> e.name }),
              )
            ok = false
          }
        }
      }
    if (!positiveOrNull(dto.durationMin, "$path.durationMin", errors)) ok = false
    if (!positiveOrNull(dto.distanceKm, "$path.distanceKm", errors)) ok = false
    if (!positiveOrNull(dto.rounds, "$path.rounds", errors)) ok = false

    val exercises = validateExercises(dto.exercises, "$path.exercises", errors)
    if (exercises == null) ok = false

    val empty =
      dto.durationMin == null &&
        dto.distanceKm == null &&
        dto.intensity == null &&
        dto.rounds == null &&
        dto.description == null &&
        exercises.isNullOrEmpty()
    if (empty) {
      errors += ImportError(path, "kevyempi vaihtoehto on tyhjä — jätä kenttä kokonaan pois")
      ok = false
    }

    if (!ok) return null
    return LighterAlternative(
      durationMin = dto.durationMin,
      distanceKm = dto.distanceKm,
      intensity = intensity,
      rounds = dto.rounds,
      roundsMin = dto.roundsMin,
      roundsMax = dto.roundsMax,
      targetPace = dto.targetPace,
      warmupSec = dto.warmupSec,
      description = dto.description,
      exercises = exercises?.takeIf { it.isNotEmpty() },
    )
  }

  private fun resolveZone(raw: String?, errors: MutableList<ImportError>): ZoneId? {
    if (raw.isNullOrBlank()) {
      errors += ImportError("plan.timeZone", "pakollinen kenttä puuttuu tai on tyhjä")
      return null
    }
    return try {
      ZoneId.of(raw)
    } catch (e: DateTimeException) {
      errors += ImportError("plan.timeZone", "tuntematon aikavyöhyke \"$raw\"")
      null
    }
  }

  private fun resolveDate(
    raw: String?,
    path: String,
    errors: MutableList<ImportError>,
  ): LocalDate? {
    if (raw.isNullOrBlank()) {
      errors += ImportError(path, "pakollinen kenttä puuttuu tai on tyhjä")
      return null
    }
    return try {
      LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
      errors += ImportError(path, "päivämäärä \"$raw\" ei ole muotoa YYYY-MM-DD")
      null
    }
  }

  private fun resolveTime(
    raw: String?,
    path: String,
    errors: MutableList<ImportError>,
  ): LocalTime? {
    if (raw.isNullOrBlank()) {
      errors += ImportError(path, "pakollinen kenttä puuttuu tai on tyhjä")
      return null
    }
    return try {
      LocalTime.parse(raw, TIME_FORMAT)
    } catch (e: DateTimeParseException) {
      errors += ImportError(path, "kellonaika \"$raw\" ei ole muotoa HH:mm")
      null
    }
  }

  private fun positiveOrNull(
    value: Int?,
    path: String,
    errors: MutableList<ImportError>,
  ): Boolean {
    if (value != null && value <= 0) {
      errors += ImportError(path, "arvon on oltava suurempi kuin 0 (oli $value)")
      return false
    }
    return true
  }

  private fun positiveOrNull(
    value: Double?,
    path: String,
    errors: MutableList<ImportError>,
  ): Boolean {
    if (value != null && value <= 0.0) {
      errors += ImportError(path, "arvon on oltava suurempi kuin 0 (oli $value)")
      return false
    }
    return true
  }

  private fun nonNegativeOrNull(
    value: Int?,
    path: String,
    errors: MutableList<ImportError>,
  ): Boolean {
    if (value != null && value < 0) {
      errors += ImportError(path, "arvo ei voi olla negatiivinen (oli $value)")
      return false
    }
    return true
  }

  private fun nonNegativeOrNull(
    value: Double?,
    path: String,
    errors: MutableList<ImportError>,
  ): Boolean {
    if (value != null && value < 0.0) {
      errors += ImportError(path, "arvo ei voi olla negatiivinen (oli $value)")
      return false
    }
    return true
  }

  private inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? =
    raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

  private fun describeEnumProblem(raw: String?, allowed: List<String>): String =
    if (raw == null) "pakollinen kenttä puuttuu (sallitut: ${allowed.joinToString(", ")})"
    else "tuntematon arvo \"$raw\" (sallitut: ${allowed.joinToString(", ")})"
}
