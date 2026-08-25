package fi.merilainen.treenivalmentaja.domain

/** One screen-sized instruction in a guided strength session. */
sealed interface ActiveWorkoutStep {
  data class Prepare(
    val round: Int,
    val rounds: Int,
    val position: Int,
    val exercise: Exercise,
  ) : ActiveWorkoutStep

  data class Perform(
    val round: Int,
    val rounds: Int,
    val position: Int,
    val exercise: Exercise,
  ) : ActiveWorkoutStep

  data class Rest(val round: Int, val seconds: Int, val nextExerciseName: String) : ActiveWorkoutStep

  data class RoundBreak(val seconds: Int, val nextRound: Int) : ActiveWorkoutStep

  data object Finish : ActiveWorkoutStep
}

data class SkippedMovement(val round: Int, val position: Int, val name: String)

/** Outcome stored on the immutable COMPLETED event. */
data class ActiveWorkoutOutcome(
  val guided: GuidedProgress,
  val skipped: List<SkippedMovement> = emptyList(),
  val sessionRpe: Int? = null,
  val feel: String? = null,
  val durationSec: Long? = null,
)

/**
 * Builds the deterministic sequence the full-screen mode renders.
 *
 * Preparation always precedes performance. Rest may end automatically, but the following
 * movement still cannot begin until its own Prepare screen's "Olen valmis" action.
 */
fun buildActiveWorkoutSteps(session: TrainingSession): List<ActiveWorkoutStep> {
  if (session.type != WorkoutType.STRENGTH) return emptyList()
  return buildActiveWorkoutSteps(
    exercises = session.exercises.orEmpty(),
    rounds = session.rounds ?: session.roundsMin ?: 1,
    roundRestSec = session.roundRestSec,
  )
}

fun buildActiveWorkoutSteps(
  exercises: List<Exercise>,
  rounds: Int,
  roundRestSec: Int?,
): List<ActiveWorkoutStep> {
  if (exercises.isEmpty()) return emptyList()
  val safeRounds = rounds.coerceAtLeast(1)

  return buildList {
    repeat(safeRounds) { roundIndex ->
      exercises.forEachIndexed { exerciseIndex, exercise ->
        add(
          ActiveWorkoutStep.Prepare(
            round = roundIndex + 1,
            rounds = safeRounds,
            position = exerciseIndex + 1,
            exercise = exercise,
          )
        )
        add(
          ActiveWorkoutStep.Perform(
            round = roundIndex + 1,
            rounds = safeRounds,
            position = exerciseIndex + 1,
            exercise = exercise,
          )
        )

        val lastExercise = exerciseIndex == exercises.lastIndex
        val lastRound = roundIndex == safeRounds - 1
        when {
          !lastExercise && (exercise.restSec ?: 0) > 0 ->
            add(
              ActiveWorkoutStep.Rest(
                round = roundIndex + 1,
                seconds = exercise.restSec!!,
                nextExerciseName = exercises[exerciseIndex + 1].name,
              )
            )
          lastExercise && !lastRound && (roundRestSec ?: 0) > 0 ->
            add(
              ActiveWorkoutStep.RoundBreak(
                seconds = roundRestSec!!,
                nextRound = roundIndex + 2,
              )
            )
        }
      }
    }
    add(ActiveWorkoutStep.Finish)
  }
}

/**
 * Which movement this is, independent of where it sits in the step list: a movement is the same
 * one whether it was reached forwards or by walking back, so the key is the round and the position
 * rather than the index.
 */
fun ActiveWorkoutStep.Perform.key(): String = "$round:$position"

/**
 * The movements the person has actually done — a skipped one has passed its step but was not
 * trained, so it counts for neither the progress meter nor the stored [GuidedProgress].
 */
fun List<ActiveWorkoutStep>.completedMovements(
  beforeStepIndex: Int,
  skippedKeys: List<String> = emptyList(),
): Int =
  take(beforeStepIndex.coerceIn(0, size)).filterIsInstance<ActiveWorkoutStep.Perform>().count {
    it.key() !in skippedKeys
  }

/** Resolves the keys the screen collected back into the movements they name, in sequence order. */
fun List<ActiveWorkoutStep>.skippedMovements(skippedKeys: List<String>): List<SkippedMovement> =
  filterIsInstance<ActiveWorkoutStep.Perform>()
    .filter { it.key() in skippedKeys }
    .map { SkippedMovement(round = it.round, position = it.position, name = it.exercise.name) }

/**
 * What is still to come **in this round**.
 *
 * Crossing the round boundary is what made the card confusing: with two movements and three rounds
 * it listed the movement being performed right now as one of the upcoming ones, because round two
 * begins with it. The round break is a step of its own with its own screen, so the round is the
 * honest horizon here.
 */
fun upcomingInRound(
  steps: List<ActiveWorkoutStep>,
  current: Int,
): List<String> {
  val round = (steps.getOrNull(current) as? ActiveWorkoutStep.Perform)?.round ?: return emptyList()
  return steps
    .drop(current + 1)
    .filterIsInstance<ActiveWorkoutStep.Perform>()
    .takeWhile { it.round == round }
    .take(3)
    .map { it.exercise.name }
}
