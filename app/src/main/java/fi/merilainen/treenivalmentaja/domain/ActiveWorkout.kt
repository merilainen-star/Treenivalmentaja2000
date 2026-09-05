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

/**
 * Outcome stored on the immutable COMPLETED event.
 *
 * [durationSec] is the gross time — the whole session, rests included — and was here first, so it
 * keeps its name. [netSec] and [movementSeconds] arrived with the stopwatch and are null on every
 * session completed before it: absent, which is the truth, rather than zero, which would say the
 * movements took no time at all.
 */
data class ActiveWorkoutOutcome(
  val guided: GuidedProgress,
  val skipped: List<SkippedMovement> = emptyList(),
  val sessionRpe: Int? = null,
  val feel: String? = null,
  val durationSec: Long? = null,
  /** The movements alone, rests excluded. */
  val netSec: Long? = null,
  /** Seconds per movement, keyed by round and position — `"2:3"` is round two, third movement. */
  val movementSeconds: Map<String, Long>? = null,
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

/**
 * Is the step at [index] part of a movement that was skipped?
 *
 * A skipped movement owns three steps — its preparation screen, the movement, and the rest that
 * follows it — and all three have to be invisible to navigation. Preparing for a movement you
 * declined is the same thing as the movement; and a rest exists to recover from work that, in this
 * case, was not done.
 *
 * The round break is deliberately not one of them: it belongs to the turn between rounds rather
 * than to any single movement, so skipping the last movement of a round does not abolish it.
 */
fun List<ActiveWorkoutStep>.belongsToSkipped(index: Int, skippedKeys: List<String>): Boolean =
  when (val step = getOrNull(index)) {
    is ActiveWorkoutStep.Perform -> step.key() in skippedKeys
    is ActiveWorkoutStep.Prepare ->
      (getOrNull(index + 1) as? ActiveWorkoutStep.Perform)?.key() in skippedKeys
    // A Rest is only ever emitted straight after the movement it belongs to.
    is ActiveWorkoutStep.Rest ->
      (getOrNull(index - 1) as? ActiveWorkoutStep.Perform)?.key() in skippedKeys
    else -> false
  }

/**
 * The step "Edellinen vaihe" should go to, skipping over anything that was left behind.
 *
 * Walking back one index at a time landed straight on a movement that had been skipped — and then
 * offered it again as the next thing to do, which is the opposite of what skipping meant. Returns
 * [from] unchanged when there is nothing behind it to go back to, so the caller can hide the
 * control rather than offer one that does nothing.
 */
fun List<ActiveWorkoutStep>.previousStep(from: Int, skippedKeys: List<String>): Int {
  for (i in (from - 1) downTo 0) {
    if (!belongsToSkipped(i, skippedKeys)) return i
  }
  return from
}

/**
 * Where a resumed workout should actually open.
 *
 * The stored index can name a skipped movement — skip one, walk back, leave, return — so a resume
 * moves forward off it rather than putting a person back in front of the thing they declined.
 */
fun List<ActiveWorkoutStep>.resumeIndex(stored: Int, skippedKeys: List<String>): Int {
  if (isEmpty()) return 0
  var i = stored.coerceIn(0, lastIndex)
  while (i < lastIndex && belongsToSkipped(i, skippedKeys)) i++
  return i
}

/** The step to advance to, passing over anything belonging to a movement that was skipped. */
fun List<ActiveWorkoutStep>.nextStep(from: Int, skippedKeys: List<String>): Int {
  if (isEmpty()) return 0
  var i = (from + 1).coerceAtMost(lastIndex)
  while (i < lastIndex && belongsToSkipped(i, skippedKeys)) i++
  return i
}
