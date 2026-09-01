package fi.merilainen.treenivalmentaja.domain

/**
 * What Oura actually returned, in the plainest terms available.
 *
 * This exists because of a real dead end: a strength session was visible in Oura's own app and
 * absent from this one, and from the outside there was no way to tell whether the API had not
 * returned it, whether it had been dropped in parsing, or whether it had been stored and not
 * displayed. Asking the person to hand over their Oura credentials so someone else could query the
 * API for them would be the wrong fix — the phone is what makes the requests, so the phone is what
 * should say what came back.
 *
 * Deliberately counts and one line per workout, not raw JSON. What is needed is "is it there", and
 * a screen full of a health API's raw response is both harder to read and more to leak in a
 * screenshot.
 */
data class OuraDiagnostics(
  /** `YYYY-MM-DD`, the range asked for. */
  val fromDate: String,
  val toDate: String,
  val readinessDays: Int = 0,
  val sleepDays: Int = 0,
  val activityDays: Int = 0,
  val workouts: List<String> = emptyList(),
  val heartRateSamples: Int = 0,
  /**
   * Of [readinessDays], how many carried at least one non-null `contributors` field.
   *
   * The same dead end this whole type was built to close, one layer deeper: a document can arrive
   * (counted in [readinessDays]) with a `contributors` object that is itself present but empty, and
   * from the outside "Oura sent no breakdown for this day" and "the app dropped it" look identical.
   * This counts what the wire response itself carried, read straight off the client's DTOs —
   * nothing routed through [fi.merilainen.treenivalmentaja.data.oura.OuraMappers] or Room first, so
   * a mapper bug cannot hide behind a passing count here. See ADR-014 in `docs/DECISIONS.md`.
   */
  val readinessWithContributors: Int = 0,
  /** Of [activityDays], how many carried a non-null `contributors.recovery_time`. */
  val activityWithRecoveryTime: Int = 0,
  /** Per-collection failures, already in Finnish. Empty when everything answered. */
  val failures: List<String> = emptyList(),
) {

  val workoutCount: Int
    get() = workouts.size
}
