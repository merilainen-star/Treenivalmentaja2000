package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate

/**
 * What the app may offer to do about a poor readiness reading.
 *
 * Deliberately an *offer*, never an action. The readiness card that preceded this was removed for
 * giving advice with nothing behind it, and the lesson taken from that is not "say nothing forever"
 * but "say only what a measurement supports, and let the person decide". Everything here is
 * decided from numbers already on the phone; nothing is inferred, and no network is involved.
 */
sealed interface ReadinessAdvice {

  /** Nothing to say. The ordinary state, including every day the ring was not worn. */
  data object None : ReadinessAdvice

  /**
   * A question worth asking this morning.
   *
   * @param concern why it is being asked, which is also what the card's wording turns on.
   * @param readiness the score behind the question. Always present — this type cannot be
   *   constructed without one, which is what keeps advice from appearing on a day with no
   *   measurement.
   * @param shiftableSessionIds sessions the programme could be shifted from, earliest first.
   *   Empty when there is nothing to move, in which case the card offers only lightening.
   * @param lightenableSessionIds today's sessions that could be started lighter. Empty when today
   *   is a rest day or its sessions are already done.
   */
  data class Offer(
    val concern: Concern,
    val readiness: Int,
    val shiftableSessionIds: List<String>,
    val lightenableSessionIds: List<String>,
  ) : ReadinessAdvice {

    /** An offer with neither option is not an offer; the use case never builds one. */
    val hasSomethingToDo: Boolean
      get() = shiftableSessionIds.isNotEmpty() || lightenableSessionIds.isNotEmpty()
  }

  /** Why the app is asking. The two cases read very differently to a person. */
  enum class Concern {
    /**
     * Yesterday's session went undone *and* yesterday's readiness was poor — the case where the
     * plan has slipped for a reason the body can account for.
     */
    MISSED_AFTER_POOR_DAY,

    /** Today's readiness is poor and today has a session that has not been done yet. */
    POOR_TODAY,
  }
}

/**
 * Decides whether this morning is worth a question, from what Oura measured and what the plan
 * asked for.
 *
 * A pure function of its inputs — no repository, no clock, no I/O — so every branch below is a
 * unit test rather than something only a real week of training could produce.
 *
 * Two rules, and no others:
 *
 *  1. **Yesterday was poor and its session went undone.** The interesting case, and the one this
 *     was built for: the plan has slipped, and the readiness number says why. Offers both moving
 *     the programme forward and starting today lighter.
 *  2. **Today is poor and today has a session.** Offers only lightening — moving the programme on
 *     the strength of a single morning's number would be a bigger claim than the measurement
 *     supports.
 *
 * Rule 1 is checked first: on a day both would fire, the missed session is the more useful thing
 * to talk about, and two cards asking about the same morning would be noise.
 */
class ReadinessAdviceUseCase {

  fun execute(
    today: LocalDate,
    recoveryByDay: Map<LocalDate, DailyRecovery>,
    sessions: List<TrainingSession>,
  ): ReadinessAdvice {
    val yesterday = today.minusDays(1)

    val yesterdaysMissed = sessions.openOn(yesterday)
    val yesterdaysReadiness = recoveryByDay[yesterday]?.readiness
    if (yesterdaysMissed.isNotEmpty() && yesterdaysReadiness != null && yesterdaysReadiness.isPoor) {
      return offer(
        concern = ReadinessAdvice.Concern.MISSED_AFTER_POOR_DAY,
        readiness = yesterdaysReadiness,
        // What "shifting the programme forward" would move: the sessions left behind, plus
        // everything still ahead of them. The engine decides how far; this only says what is on
        // the table.
        shiftable = yesterdaysMissed.map { it.id },
        lightenable = sessions.lightenableOn(today),
      )
    }

    val todaysReadiness = recoveryByDay[today]?.readiness
    if (todaysReadiness != null && todaysReadiness.isPoor) {
      val lightenable = sessions.lightenableOn(today)
      if (lightenable.isNotEmpty()) {
        return offer(
          concern = ReadinessAdvice.Concern.POOR_TODAY,
          readiness = todaysReadiness,
          shiftable = emptyList(),
          lightenable = lightenable,
        )
      }
    }

    return ReadinessAdvice.None
  }

  private fun offer(
    concern: ReadinessAdvice.Concern,
    readiness: Int,
    shiftable: List<String>,
    lightenable: List<String>,
  ): ReadinessAdvice =
    ReadinessAdvice.Offer(concern, readiness, shiftable, lightenable)
      .takeIf { it.hasSomethingToDo } ?: ReadinessAdvice.None

  /** Sessions still open on a day that has already passed — that is what "missed" means here. */
  private fun List<TrainingSession>.openOn(date: LocalDate): List<TrainingSession> =
    filter { it.status.isOpen && it.scheduledDate == date.toString() }
      .sortedBy { it.remindAtUtc }

  /**
   * Today's sessions that could still be started lighter.
   *
   * A session already carrying the lighter variant is excluded: offering to lighten it again would
   * be a button that cuts the same session twice.
   */
  private fun List<TrainingSession>.lightenableOn(date: LocalDate): List<String> =
    filter {
      it.scheduledDate == date.toString() &&
        !it.appliedLighterVariant &&
        it.status.canTransitionTo(SessionStatus.REPLACED_WITH_LIGHTER_VERSION)
    }
      .sortedBy { it.remindAtUtc }
      .map { it.id }

  private companion object {

    /**
     * Below this, Oura's own presentation of the score stops saying "good" and starts saying "pay
     * attention" — see [DailyRecovery.readinessLabel], whose bands this deliberately shares rather
     * than inventing a second opinion about the same number.
     *
     * The threshold is on the *score*, never on its absence: a day the ring was not worn has no
     * readiness at all, and treating that as a low one is exactly the "missing is not zero"
     * mistake the whole Oura layer is built to avoid.
     */
    const val POOR_BELOW = 70
  }

  private val Int.isPoor: Boolean
    get() = this < POOR_BELOW
}
