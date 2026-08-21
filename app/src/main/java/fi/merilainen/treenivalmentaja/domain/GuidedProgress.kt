package fi.merilainen.treenivalmentaja.domain

/**
 * How far a guided strength session actually got, counted in ticked movements.
 *
 * The guided workout is a sequence — round 2's third movement cannot be done before its second —
 * so how far it got is one number rather than a set of independent flags. [done] is that number,
 * and [rounds] × [perRound] is the sequence it was counted against.
 *
 * **The shape is stored with the count, and that is the point.** A count alone cannot be read
 * later: "6" means a session two thirds finished or one barely started depending on a list that
 * may since have been swapped by "Kevyempi versio". Recording both makes the record self-contained,
 * and lets a reader notice when the plan it was counted against is no longer the plan on screen.
 *
 * This says which movements were performed, never at what load — the plan's own prescription is
 * the only account of that. See `docs/DATA_MODEL.md` § 3.
 */
data class GuidedProgress(val done: Int, val rounds: Int, val perRound: Int) {

  /** Movements in the whole session: every round's worth. */
  val total: Int
    get() = rounds * perRound

  /**
   * True when every movement was ticked off.
   *
   * A session with nothing in it is **not** complete: `0 >= 0` is true arithmetically and false in
   * every sense that matters here, and letting it through would tell the coach a plan was carried
   * out in full when the plan was empty.
   */
  val isComplete: Boolean
    get() = total > 0 && done >= total
}
