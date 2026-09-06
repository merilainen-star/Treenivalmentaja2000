package fi.merilainen.treenivalmentaja.domain

/**
 * The next-programme flow, from the question to the saved plan.
 *
 * Absent (`null`) until the person asks for it, so the final report is a report until they choose
 * to act on it. [Choosing] is a state rather than a screen because the answer to "what should the
 * next block emphasise" is the input the whole request hangs on, and it is asked once, in front of
 * the person, rather than guessed from the data.
 */
sealed interface NextProgramState {

  /** The goal picker and the whole-programme rating are showing. Nothing has been sent. */
  data object Choosing : NextProgramState

  data class Loading(val request: NextProgramRequest) : NextProgramState

  /**
   * A plan came back and **the validator accepted it**. Nothing is saved yet.
   *
   * [summary] is computed from the plan's own sessions rather than taken from the model — see
   * [summariseProgramPlan]. [rawJson] is kept because that, not the summary, is what gets imported:
   * re-serialising a parsed plan would import something subtly different from what was checked.
   */
  data class Ready(
    val summary: NextProgramSummary,
    val rawJson: String,
    val prompt: String,
    val request: NextProgramRequest,
  ) : NextProgramState

  /**
   * The model answered, and the answer is not a plan this app can accept.
   *
   * Shown with the validator's own Finnish messages rather than a generic failure, because they say
   * exactly what is wrong — and because the same messages already serve a hand-written import. A
   * retry is offered: this is the failure mode a second attempt actually fixes.
   */
  data class Invalid(
    val errors: List<String>,
    val prompt: String,
    val request: NextProgramRequest,
  ) : NextProgramState

  data class Failed(val message: String, val canRetry: Boolean, val request: NextProgramRequest?) :
    NextProgramState

  /** The plan is being written to the database. */
  data object Importing : NextProgramState

  /** Saved and active. The calendar behind this card now shows the new programme. */
  data class Imported(val planName: String, val sessions: Int) : NextProgramState
}
