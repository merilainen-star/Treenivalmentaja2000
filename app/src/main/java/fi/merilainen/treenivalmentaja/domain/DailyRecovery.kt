package fi.merilainen.treenivalmentaja.domain

/**
 * What Oura knows about one day, as the app reads it.
 *
 * Every score is nullable and that is the whole point of this type. Oura returns a document with no
 * `score` for a day the ring was not worn — not an absent document — so "there is a day here and we
 * do not know its readiness" is a state the app must be able to hold and to say out loud. A zero
 * would be a lie that type-checks.
 */
data class DailyRecovery(
  /** `YYYY-MM-DD`, local date. */
  val date: String,
  val readiness: Int? = null,
  val sleep: Int? = null,
  val activity: Int? = null,
  /**
   * Average heart-rate variability during the night's sleep, in milliseconds.
   *
   * A **measurement**, where [readiness] is Oura's opinion of one — which is the whole reason it is
   * fetched separately. `readiness` says how the morning compares to this athlete's own baseline;
   * this says what the number actually was, and means the same thing next season.
   *
   * From the night's `long_sleep` period, never averaged across naps — see `OuraMappers`.
   */
  val averageHrvMs: Int? = null,
  /**
   * The lowest heart rate during the night — the resting figure Oura's own app shows.
   *
   * Named for what it is used as rather than for the field it comes from (`lowest_heart_rate`),
   * because "resting heart rate" is what a person reading a training analysis means by it.
   */
  val restingHeartRate: Int? = null,
  /** Average heart rate across the night. Read beside [restingHeartRate], not instead of it. */
  val sleepHeartRate: Int? = null,
  /** When this was last read from Oura. Epoch millis UTC. */
  val fetchedAtUtc: Long = 0L,
) {

  /**
   * True when Oura had a day but no numbers in it — the ring was off, or the night was partial.
   *
   * The nightly measurements count: a day with an HRV reading and no scores is not an empty day, and
   * saying it was would hide the very thing that was measured.
   */
  val isEmpty: Boolean
    get() =
      readiness == null &&
        sleep == null &&
        activity == null &&
        averageHrvMs == null &&
        restingHeartRate == null &&
        sleepHeartRate == null

  /**
   * A word for the readiness number.
   *
   * The bands follow Oura's own presentation of the score, and stop there: this says how the number
   * reads, never what to do about it. Advice with a measurement behind it is a later decision, and
   * advice without one is what the previous version of this card was removed for.
   */
  val readinessLabel: String?
    get() =
      readiness?.let {
        when {
          it >= 85 -> "Optimaalinen"
          it >= 70 -> "Hyvä"
          else -> "Kiinnitä huomiota"
        }
      }
}
