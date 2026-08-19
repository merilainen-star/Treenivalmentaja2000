package fi.merilainen.treenivalmentaja.domain

/**
 * The athlete's training load on one day — fitness, fatigue, and the gap between them.
 *
 * **Dated on purpose.** Load decays every day: ATL on roughly a 7-day time constant, CTL on 42. A
 * figure without its date is a figure that cannot be checked for staleness, which is exactly how the
 * first version of the AI analysis came to tell the model about a fatigue three days gone. [date]
 * travels into the prompt with the numbers so the reader — and the model — can see how current they
 * are.
 *
 * Read from intervals.icu's daily wellness series, never from an activity's own `icu_atl`/`icu_ctl`:
 * those are frozen at the moment of a session and never decay.
 */
data class DailyTrainingLoad(
  /** `YYYY-MM-DD` — the day these figures describe, which may be earlier than the day asked for. */
  val date: String,
  /** Acute training load: fatigue, the short rolling average. */
  val acute: Double? = null,
  /** Chronic training load: fitness, the long rolling average. */
  val chronic: Double? = null,
  /** CTL change per week. Shown to nobody yet; one column, and the series' own summary of itself. */
  val rampRate: Double? = null,
) {

  /**
   * Training stress balance — fitness minus fatigue.
   *
   * `null` unless both halves exist. The pair is what means something; one alone invites inferring
   * the other, which is the mistake this whole type exists to stop.
   */
  val stressBalance: Double?
    get() = if (chronic != null && acute != null) chronic - acute else null
}
