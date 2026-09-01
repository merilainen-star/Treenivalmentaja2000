package fi.merilainen.treenivalmentaja.domain

/** Which of the two analyses a session can be asked for, if either. */
enum class AiAnalysisKind {
  /** How it went: load and intensity against that morning's recovery. */
  COMPLETED,

  /** How to execute it: the planned load against the current recovery trend. */
  UPCOMING,
}

/**
 * Whether a session may be analysed, and as which of the two kinds.
 *
 * A pure function of a status and a day offset, and separate from the UI for the usual reason on
 * this project: the windows are a decision, and a decision belongs somewhere a test can reach it.
 *
 * **The two windows are different sizes, and that asymmetry is the whole point.**
 *
 *  - *Completed*, seven days back: a session is worth reviewing while it is still in the training
 *    week it belonged to. Beyond that the recovery data around it has been superseded by everything
 *    since, and the analysis would be archaeology rather than coaching. It also keeps the button off
 *    every one of the hundreds of rows the week list can scroll back through.
 *  - *Upcoming*, three days forward: recovery cannot be meaningfully predicted further out than
 *    that. An analysis of a session scheduled for next month would be the model inventing a
 *    trajectory from data that says nothing about it — confident, plausible, and unfounded. Refusing
 *    to offer the button is the honest version of "we do not know".
 *
 * The two are decided by `status` and cannot both apply, so a session never shows two buttons.
 */
object AiAnalysisAvailability {

  /** Inclusive: today counts, and so does a session done seven days ago. */
  const val COMPLETED_DAYS_BACK = 7

  /** Inclusive: today counts, and so does a session three days out. */
  const val UPCOMING_DAYS_FORWARD = 3

  /**
   * @param status the session's current state.
   * @param dayOffset days from today — negative in the past, 0 today, positive ahead.
   * @return which analysis to offer, or `null` for no button at all.
   */
  fun kindFor(status: SessionStatus, dayOffset: Int): AiAnalysisKind? =
    when (status) {
      // All three have something to review: a full session, one ended early on purpose
      // (INTERRUPTED), or one still under way with whatever the guided list has recorded so far
      // (STARTED). `AnalysisPromptBuilder` already renders partial guided progress honestly —
      // "3/5 liikettä tehty" — so nothing downstream needs to know which of the three it was.
      SessionStatus.COMPLETED,
      SessionStatus.STARTED,
      SessionStatus.INTERRUPTED ->
        AiAnalysisKind.COMPLETED.takeIf { dayOffset in -COMPLETED_DAYS_BACK..0 }

      // Only the two states that mean "still ahead of you and unchanged".
      //
      // REPLACED_WITH_LIGHTER_VERSION is absent for a different reason — the lightening has already
      // happened, so the advice the analysis exists to give has already been taken.
      SessionStatus.PLANNED,
      SessionStatus.NOTIFIED ->
        AiAnalysisKind.UPCOMING.takeIf { dayOffset in 0..UPCOMING_DAYS_FORWARD }

      // SKIPPED now means "never touched" (see SessionStatus) — nothing completed to assess and
      // nothing upcoming to advise on. The rest are closed rows the screens do not draw at all.
      else -> null
    }
}
