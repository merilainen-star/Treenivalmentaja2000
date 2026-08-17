package fi.merilainen.treenivalmentaja.domain

/**
 * What one session's analysis card is showing.
 *
 * Held per session id rather than as a single screen-wide value, so scrolling the week list does not
 * lose an open analysis and more than one card can be open at once. Nothing here reaches the
 * database: an analysis lives exactly as long as the ViewModel that holds it, which is the other
 * half of "this feature changes nothing" — there is no stored verdict to go stale, and no history of
 * machine opinions accumulating beside the training log.
 */
sealed interface AiAnalysisState {

  /** The request is in flight. The button is replaced by a spinner while this is the state. */
  data object Loading : AiAnalysisState

  /**
   * An answer arrived.
   *
   * @param text the model's prose, shown as written.
   * @param prompt exactly what was sent, for the "Näytä pyyntö" panel. Kept beside the answer rather
   *   than rebuilt on demand: the plan or the recovery data may have changed since, and a panel that
   *   showed a *reconstruction* of the request would be showing something that was never sent.
   */
  data class Loaded(val text: String, val prompt: String) : AiAnalysisState

  /** Something went wrong. [message] is already Finnish; [canRetry] decides whether to offer it. */
  data class Failed(val message: String, val canRetry: Boolean) : AiAnalysisState
}
