package fi.merilainen.treenivalmentaja.domain

import java.time.LocalDate

/**
 * Where "ei nyt" is kept once the screen is gone.
 *
 * The refusal used to live in a `MutableStateFlow` in the ViewModel and nowhere else, so it lasted
 * exactly as long as the process: every reinstall, every update to a new APK, every cold start
 * after Android reclaimed the app asked the same question again about the same old sessions. A
 * refusal the app forgets on restart is not an answer, it is a nag with extra steps.
 *
 * The stored value is a plan-zone date, which is what keeps this "not today" rather than "never" —
 * see `WorkoutViewModel.missedProposalDismissedFor`.
 */
interface MissedProposalDismissalStore {
  /** The day the user last refused, or null if they never have. */
  suspend fun dismissedFor(): LocalDate?

  suspend fun setDismissedFor(date: LocalDate)
}
