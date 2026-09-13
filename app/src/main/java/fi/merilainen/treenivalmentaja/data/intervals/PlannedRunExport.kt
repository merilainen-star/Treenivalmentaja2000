package fi.merilainen.treenivalmentaja.data.intervals

import com.squareup.moshi.Json
import fi.merilainen.treenivalmentaja.domain.TrainingSession
import fi.merilainen.treenivalmentaja.domain.validRunSteps
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal data class PlannedRunEvent(
  @Json(name = "external_id") val externalId: String,
  @Json(name = "start_date_local") val startDateLocal: String,
  val name: String,
  val description: String,
  val category: String = "WORKOUT",
  val type: String = "Run",
)

internal data class CalendarEvent(
  @Json(name = "external_id") val externalId: String? = null,
  @Json(name = "start_date_local") val startDateLocal: String? = null,
  val category: String? = null,
  val type: String? = null,
  @Json(name = "workout_doc") val workoutDoc: WorkoutDocument? = null,
  @Json(name = "push_errors") val pushErrors: List<WorkoutPushError>? = null,
)

internal data class WorkoutDocument(val steps: List<Map<String, Any?>>? = null)
internal data class WorkoutPushError(val service: String? = null, val message: String? = null)

internal const val RUN_EXPORT_PREFIX = "treenivalmentaja-run-"

/** Native text is the API's supported parser input; workout_doc alone does not reliably sync. */
internal fun TrainingSession.toPlannedRunEvent(): PlannedRunEvent {
  val steps = requireNotNull(runSteps) { "$scheduledDate: määritä juoksun vaiheet ennen vientiä." }
  require(steps.validRunSteps()) { "$scheduledDate: tarkista juoksun vaiheet." }
  val identity = MessageDigest.getInstance("SHA-256")
    .digest("$planId\u0000$id".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
  return PlannedRunEvent(
    externalId = RUN_EXPORT_PREFIX + identity,
    startDateLocal = LocalDate.parse(scheduledDate).atTime(
      scheduledTime?.let(LocalTime::parse) ?: LocalTime.MIDNIGHT,
    ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
    name = "Juoksu · $scheduledDate",
    description = steps.joinToString("\n\n") { step ->
      // A label belongs on its own line, so numeric text in it cannot become a target.
      val label = step.name.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim()
      buildString {
        append("Vaihe: $label\n- ")
        append(step.durationSec?.let { "${it}s" } ?: "${step.distanceMeters}mtr")
        step.paceSecPerKm?.let {
          append(" ${it / 60}:${(it % 60).toString().padStart(2, '0')}/km Pace")
        }
      }
    },
  )
}
