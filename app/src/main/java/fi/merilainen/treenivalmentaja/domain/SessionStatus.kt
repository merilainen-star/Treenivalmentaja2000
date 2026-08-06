package fi.merilainen.treenivalmentaja.domain

/**
 * Lifecycle state of a single [fi.merilainen.treenivalmentaja.data.local.entity.WorkoutSessionEntity].
 *
 * The transition table below is normative and mirrors `docs/TRAINING_ENGINE.md`. Any transition
 * outside it is a programming error: the repository rejects it and writes nothing — neither the
 * session update nor a `SessionEvent`.
 */
enum class SessionStatus(val title: String) {
  /** Scheduled, reminder not yet fired. Initial state of every imported session. */
  PLANNED("Suunniteltu"),

  /** The AlarmManager reminder has fired; the user has been told but has not acted. */
  NOTIFIED("Muistutettu"),

  /** The user has started the session. */
  STARTED("Aloitettu"),

  /** Done — manually confirmed or matched to an Oura workout. */
  COMPLETED("Tehty"),

  /** Intentionally not done, and not moved to another day. */
  SKIPPED("Ohitettu"),

  /**
   * Moved to another day. This row is closed; a new session row carries the new date and points
   * back here via `originalSessionId`.
   */
  RESCHEDULED("Siirretty"),

  /**
   * The user chose the lighter alternative. Not terminal — the session still has to be completed
   * or skipped afterwards.
   */
  REPLACED_WITH_LIGHTER_VERSION("Kevyempi versio"),

  /** Illness mode paused this session. It resumes or is rescheduled on recovery. */
  PAUSED_DUE_TO_ILLNESS("Tauolla (sairaus)"),

  /** Removed from the plan entirely. Never counted as a missed session. */
  CANCELLED("Peruttu");

  /** Statuses this one may move to. Empty for terminal statuses. */
  val allowedTransitions: Set<SessionStatus>
    get() =
      when (this) {
        PLANNED ->
          setOf(
            NOTIFIED,
            STARTED,
            COMPLETED,
            SKIPPED,
            RESCHEDULED,
            REPLACED_WITH_LIGHTER_VERSION,
            PAUSED_DUE_TO_ILLNESS,
            CANCELLED,
          )
        NOTIFIED ->
          setOf(
            STARTED,
            COMPLETED,
            SKIPPED,
            RESCHEDULED,
            REPLACED_WITH_LIGHTER_VERSION,
            PAUSED_DUE_TO_ILLNESS,
            CANCELLED,
          )
        STARTED -> setOf(COMPLETED, SKIPPED, CANCELLED)
        REPLACED_WITH_LIGHTER_VERSION ->
          setOf(STARTED, COMPLETED, SKIPPED, RESCHEDULED, PAUSED_DUE_TO_ILLNESS, CANCELLED)
        PAUSED_DUE_TO_ILLNESS -> setOf(PLANNED, RESCHEDULED, CANCELLED)
        COMPLETED, SKIPPED, RESCHEDULED, CANCELLED -> emptySet()
      }

  /** A terminal status can never transition anywhere. */
  val isTerminal: Boolean
    get() = allowedTransitions.isEmpty()

  /** True when this session should still appear as actionable work in the UI. */
  val isOpen: Boolean
    get() = !isTerminal

  fun canTransitionTo(target: SessionStatus): Boolean = target in allowedTransitions
}
