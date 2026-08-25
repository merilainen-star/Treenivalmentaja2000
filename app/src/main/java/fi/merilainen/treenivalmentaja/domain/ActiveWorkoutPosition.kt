package fi.merilainen.treenivalmentaja.domain

/**
 * Where a guided session was left.
 *
 * The step index and the skipped movements used to live in `rememberSaveable` inside the screen.
 * That survives the process being killed — the saved instance state carries it — but **not leaving
 * the screen**: the back arrow pops the navigation destination, and popping destroys the state it
 * was holding. So a workout interrupted by backgrounding the phone resumed, and the same workout
 * interrupted by walking out of the screen started again from the first movement. Both are the
 * same interruption to the person doing the training.
 *
 * One session at a time, because one workout at a time is what a person does. [sessionId] is stored
 * with the position so a different session cannot inherit a stale index.
 */
data class ActiveWorkoutPosition(
  val sessionId: String,
  val stepIndex: Int,
  val skippedKeys: List<String> = emptyList(),
) {
  /** `sessionId|stepIndex|key,key` — see [parse] for why this is not JSON. */
  fun encode(): String = listOf(sessionId, stepIndex.toString(), skippedKeys.joinToString(",")).joinToString("|")

  companion object {
    /**
     * Reads back [encode]'s output, or `null` for anything it does not recognise.
     *
     * A delimited string rather than JSON because there are three fields and no nesting, and
     * because the movement keys are `round:position` — a colon, not a pipe or a comma, so neither
     * separator can appear inside a value. Anything unparseable reads as "no stored position",
     * which starts the workout rather than refusing to open it: a corrupt preference must not be
     * able to lock a person out of their own session.
     */
    fun parse(raw: String?): ActiveWorkoutPosition? {
      val parts = raw?.split("|") ?: return null
      if (parts.size != 3) return null
      val sessionId = parts[0].takeIf { it.isNotBlank() } ?: return null
      val stepIndex = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
      val keys = parts[2].split(",").filter { it.isNotBlank() }
      return ActiveWorkoutPosition(sessionId, stepIndex, keys)
    }
  }
}

/** Where the position is kept between visits to the screen. */
interface ActiveWorkoutProgressStore {
  suspend fun load(): ActiveWorkoutPosition?

  suspend fun save(position: ActiveWorkoutPosition)

  /** Called when the session is finished or abandoned, so the next one starts from the beginning. */
  suspend fun clear()
}

/** Whether the stored position has been read yet, so the screen can wait rather than guess. */
sealed interface ActiveWorkoutPositionState {
  data object Loading : ActiveWorkoutPositionState

  /** [value] is `null` when nothing was stored for this session. */
  data class Ready(val value: ActiveWorkoutPosition?) : ActiveWorkoutPositionState
}
