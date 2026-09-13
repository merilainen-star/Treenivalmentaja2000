package fi.merilainen.treenivalmentaja.domain

/** Transient simulation only: no IDs, persistence, alarms or completion events. */
data class TrialStep(val title: String, val detail: String, val seconds: Int? = null, val meters: Int? = null)

fun trialSteps(session: TrainingSession): List<TrialStep> {
  session.runSteps?.takeIf { it.isNotEmpty() }?.let { steps ->
    return steps.map { TrialStep(it.name, it.summary(), it.durationSec, it.distanceMeters) }
  }
  val count = session.exercises.orEmpty().size.toLong() * (session.rounds ?: session.roundsMin ?: 1).toLong()
  if (count > 5_000) return listOf(TrialStep("Liian monta kokeiluvaihetta", "Jaa harjoitus pienempiin osiin kokeilua varten."))
  return buildActiveWorkoutSteps(session).map { step ->
    when (step) {
      is ActiveWorkoutStep.Prepare -> TrialStep("Valmistaudu: ${step.exercise.name}", step.exercise.notes.orEmpty())
      is ActiveWorkoutStep.Perform -> TrialStep(step.exercise.name,
        listOfNotNull(step.exercise.reps?.let { "$it toistoa" }, step.exercise.notes).joinToString("\n"), step.exercise.durationSec)
      is ActiveWorkoutStep.Rest -> TrialStep("Palautus", "Seuraava: ${step.nextExerciseName}", step.seconds)
      is ActiveWorkoutStep.RoundBreak -> TrialStep("Kierrospalautus", "Seuraava kierros: ${step.nextRound}", step.seconds)
      ActiveWorkoutStep.Finish -> TrialStep("Harjoitus päättyy", "Kokeilua ei tallenneta harjoitushistoriaan.")
    }
  }.ifEmpty { listOf(TrialStep(session.type.title, session.description.orEmpty(), session.durationMin?.toLong()?.times(60)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt())) }
}

data class TrialProgress(val index: Int = 0, val elapsed: Int = 0, val running: Boolean = false) {
  fun next(steps: List<TrialStep>) = copy(index = (index + 1).coerceAtMost(steps.size), elapsed = 0, running = false)
  fun tick(steps: List<TrialStep>, seconds: Int): TrialProgress {
    val limit = steps.getOrNull(index)?.seconds ?: return copy(running = false)
    if (!running) return this
    val nextElapsed = elapsed.toLong() + seconds.coerceAtLeast(0)
    return if (nextElapsed >= limit) next(steps) else copy(elapsed = nextElapsed.toInt())
  }
}
