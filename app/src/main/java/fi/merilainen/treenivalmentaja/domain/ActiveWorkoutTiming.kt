package fi.merilainen.treenivalmentaja.domain

/**
 * What the clocks of a guided session read, accumulated step by step.
 *
 * **There are three numbers, not four.** A session already showed one clock — "Kesto" — and that
 * clock was always the gross time: it starts with the workout and never stops. So this adds the
 * net time and the current item's own time, and renames the one that was already there. Anyone
 * reading the header sees one total, one subtotal, and what the subtotal is currently growing by.
 *
 * | Number | What it counts |
 * | --- | --- |
 * | Gross | Everything, from "Aloita treeni" to "Tallenna treeni". |
 * | Net | [movementSeconds] summed — the movements alone. |
 * | Current | The step on screen: this movement, or this gap. |
 *
 * Gross is not stored here because it is not accumulated: it is one subtraction from the moment
 * the workout began, and deriving it from parts would make it disagree with itself the first time
 * a step boundary was missed.
 */
data class ActiveWorkoutTiming(
  /**
   * Seconds spent performing, keyed by `ActiveWorkoutStep.Perform.key()` — the round and position
   * rather than the step index, so a movement reached by walking back is the same movement.
   *
   * Time **adds** across visits. Going back to a movement and doing it again is time spent on it,
   * and so is going back to look at it; separating those would need the app to know why someone
   * returned, which it cannot.
   */
  val movementSeconds: Map<String, Long> = emptyMap(),
  /**
   * Seconds of gap **after** each movement, keyed the same way.
   *
   * A gap belongs to the movement it follows, which is how the plan already talks about rests:
   * `restSec` sits on the exercise and means "then rest this long". A gap is the whole stretch —
   * the rest card and the preparation after it — so this is the honest answer to "how long was the
   * break after the press-ups", not just how long one card was up.
   *
   * The walk to the mat before the first movement follows nothing, so it is counted only in
   * [betweenSeconds].
   */
  val restSeconds: Map<String, Long> = emptyMap(),
  /** Seconds spent preparing, resting and between rounds — everything that is not a movement. */
  val betweenSeconds: Long = 0,
) {

  /**
   * The movements alone, leaving out anything in [skippedKeys].
   *
   * Skipped time is subtracted here rather than deleted when the movement is skipped, because the
   * two happen in the wrong order to delete: the skip changes the step, and the step's own seconds
   * are only banked as it leaves the screen. Filtering at the point of reading is indifferent to
   * that order — and to a movement being skipped, walked back to, and done after all.
   */
  fun netSeconds(skippedKeys: Collection<String> = emptyList()): Long =
    movementSeconds.entries.filter { it.key !in skippedKeys }.sumOf { it.value }

  /** Adds time to the movement [key] owns. */
  fun plusMovement(key: String, seconds: Long): ActiveWorkoutTiming =
    if (seconds <= 0) this
    else copy(movementSeconds = movementSeconds + (key to (movementSeconds[key] ?: 0) + seconds))

  /** Adds time to everything that is not a movement. */
  fun plusBetween(seconds: Long): ActiveWorkoutTiming =
    if (seconds <= 0) this else copy(betweenSeconds = betweenSeconds + seconds)

  /**
   * Adds gap time to the movement it followed.
   *
   * Called alongside [plusBetween] rather than instead of it: the same seconds are one movement's
   * rest *and* part of the session's total gap time, and the two are read for different questions.
   */
  fun plusRest(afterKey: String, seconds: Long): ActiveWorkoutTiming =
    if (seconds <= 0) this
    else copy(restSeconds = restSeconds + (afterKey to (restSeconds[afterKey] ?: 0) + seconds))

  /**
   * What each movement cost, skipped ones left out.
   *
   * The seconds a skipped movement collected are real — someone stood in front of the card before
   * declining it — but they were not training, so they belong in neither the net time nor the
   * summary. Gross keeps them, as gross keeps everything.
   */
  fun performed(skippedKeys: Collection<String> = emptyList()): Map<String, Long> =
    movementSeconds.filterKeys { it !in skippedKeys }

  /** The rests, skipped movements' left out for the same reason their own seconds are. */
  fun rests(skippedKeys: Collection<String> = emptyList()): Map<String, Long> =
    restSeconds.filterKeys { it !in skippedKeys }
}

/**
 * The movement a gap at [index] follows, or `null` before the first one.
 *
 * Walks back rather than assuming the step immediately before: a gap can span a rest and the
 * preparation after it, and both belong to the movement that ended when the gap began.
 */
fun List<ActiveWorkoutStep>.precedingMovementKey(index: Int): String? {
  for (i in (index - 1) downTo 0) {
    (getOrNull(i) as? ActiveWorkoutStep.Perform)?.let { return it.key() }
  }
  return null
}

/**
 * How long the gap at [index] was allowed to be, or `null` when nothing planned it.
 *
 * **A gap is wider than its rest card**, and this is the point the warning turns on. A rest counts
 * down and hands over automatically at zero — but it hands over to the *preparation* screen, and
 * the person is still not training while they read it and fetch the kettlebell. Measuring only the
 * rest card would make an overrun almost unobservable: the card leaves the screen at the very
 * moment it would start being late.
 *
 * So the gap runs from the end of one movement to the start of the next, across every step in
 * between, and it is measured against the rest that opened it. A preparation screen with no rest
 * before it — the first movement of the session, or a movement whose plan asked for no rest —
 * returns `null` and is never late, because nothing said how long it should take.
 */
fun List<ActiveWorkoutStep>.gapTargetSeconds(index: Int): Int? {
  if (getOrNull(index) is ActiveWorkoutStep.Perform) return null
  for (i in index downTo 0) {
    when (val step = getOrNull(i)) {
      is ActiveWorkoutStep.Rest -> return step.seconds
      is ActiveWorkoutStep.RoundBreak -> return step.seconds
      is ActiveWorkoutStep.Perform -> return null
      else -> Unit
    }
  }
  return null
}

/** True once a gap has outstayed what the plan allowed it. Equal to the target is not over it. */
fun isGapOverrun(elapsedSeconds: Long, targetSeconds: Int?): Boolean =
  targetSeconds != null && targetSeconds > 0 && elapsedSeconds > targetSeconds

/**
 * The measured movements in the order they were performed, named.
 *
 * Keyed data is stored by round and position because that is what identifies a movement; a person
 * reading a summary wants the name. A key with no measurement — a movement skipped, or one whose
 * step never closed — is simply absent, so the list never claims a movement took no time.
 *
 * The same name appears once per round, which is correct: three rounds of goblet squats are three
 * separate efforts, and averaging them would hide the one that took twice as long as the others.
 */
fun List<ActiveWorkoutStep>.movementTimes(seconds: Map<String, Long>): List<Pair<String, Long>> =
  filterIsInstance<ActiveWorkoutStep.Perform>().mapNotNull { step ->
    seconds[step.key()]?.let { step.exercise.name to it }
  }
