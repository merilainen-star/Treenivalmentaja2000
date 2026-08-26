package fi.merilainen.treenivalmentaja.domain

/**
 * The one-line answer to "how much work is this?", when the session has one.
 *
 * A plan can describe a session two ways. A gym session names sets and reps per lift and they are
 * usually the same across the lifts — "3 × 12" is then a true statement about the whole session. A
 * circuit names movements and repeats the list, and there "3 × 12" is not a fact about anything:
 * the movements have different rep counts, and the number that is shared is the round count.
 *
 * So this returns the sets-and-reps line **only when every movement agrees on it**, and `null`
 * otherwise, which is the caller's cue to state the rounds instead. Nothing here averages or picks
 * a representative movement — a summary that invents a number is worse than no summary.
 */
fun uniformSetsAndReps(exercises: List<Exercise>): String? {
  if (exercises.isEmpty()) return null
  val first = exercises.first()
  val sets = first.sets ?: return null
  val reps = first.reps ?: return null
  if (sets < 1 || reps < 1) return null
  val allAgree = exercises.all { it.sets == sets && it.reps == reps && it.perSide == first.perSide }
  if (!allAgree) return null
  val perSide = if (first.perSide == true) " / puoli" else ""
  return "$sets × $reps$perSide"
}
