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

data class ActiveWorkoutProgress(
  val stepIndex: Int = 0,
  val skipped: List<SkippedMovement> = emptyList(),
) {
  fun current(steps: List<ActiveWorkoutStep>): ActiveWorkoutStep =
    steps[stepIndex.coerceIn(0, steps.lastIndex)]

  fun advance(steps: List<ActiveWorkoutStep>): ActiveWorkoutProgress =
    copy(stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex))

  fun back(): ActiveWorkoutProgress = copy(stepIndex = (stepIndex - 1).coerceAtLeast(0))

  fun skip(steps: List<ActiveWorkoutStep>): ActiveWorkoutProgress {
    val perform = current(steps) as? ActiveWorkoutStep.Perform ?: return this
    val skippedMovement =
      SkippedMovement(
        round = perform.round,
        position = perform.position,
        name = perform.exercise.name,
      )
    return advance(steps).copy(skipped = (skipped + skippedMovement).distinct())
  }
}

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

fun List<ActiveWorkoutStep>.completedMovements(beforeStepIndex: Int): Int =
  take(beforeStepIndex.coerceIn(0, size)).count { it is ActiveWorkoutStep.Perform }
