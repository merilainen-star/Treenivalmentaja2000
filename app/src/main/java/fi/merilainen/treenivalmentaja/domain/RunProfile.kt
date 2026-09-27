package fi.merilainen.treenivalmentaja.domain

import java.util.Locale

enum class RunPhase(val title: String) {
  EASY("Kevyt"), WORK("Veto"), SPRINT("Sprintti"), STRIDE("Kiihdytys"), RECOVERY("Palautus"), UNKNOWN("Vaihe")
}

/** Labels describe the plan, never measured effort. Unknown names stay neutral. */
fun RunStep.phase(): RunPhase {
  val label = name.lowercase(Locale.ROOT)
  return when {
    listOf("palaut", "recovery", "rest").any(label::contains) -> RunPhase.RECOVERY
    listOf("kiihdy", "rullau", "stride").any(label::contains) -> RunPhase.STRIDE
    "sprint" in label && "ei sprint" !in label -> RunPhase.SPRINT
    listOf("lämm", "verrytt", "jäähd", "warm", "cool", "kevyt", "helppo").any(label::contains) -> RunPhase.EASY
    listOf("veto", "intervall", "reipas", "cooper", "tempo").any(label::contains) -> RunPhase.WORK
    else -> RunPhase.UNKNOWN
  }
}

/** Identical prescriptions are counted; the ordered chart retains their actual positions. */
fun List<RunStep>.compactPrescription(): List<String> =
  groupBy { listOf(it.phase(), it.durationSec, it.distanceMeters, it.paceSecPerKm,
    if (it.phase() == RunPhase.UNKNOWN) it.name else null) }
    .map { (_, steps) ->
      val step = steps.first()
      val amount = step.durationSec?.let { if (it % 60 == 0) "${it / 60} min" else "$it s" }
        ?: "${step.distanceMeters} m"
      val label = if (step.phase() == RunPhase.UNKNOWN) step.name else step.phase().title
      buildString {
        append(label)
        append(" · ")
        if (steps.size > 1) append("${steps.size} × ")
        append(amount)
        step.paceSecPerKm?.let { append(" · ${it / 60}:${(it % 60).toString().padStart(2, '0')} /km") }
      }
    }
