package fi.merilainen.treenivalmentaja.data.importer

/**
 * A single validation problem, addressed by its position in the JSON document.
 *
 * [path] uses the same notation as the schema doc, e.g. `weeks[0].sessions[2].time`.
 * [message] is written in Finnish and is meant to be shown to the user verbatim.
 */
data class ImportError(val path: String, val message: String) {
  override fun toString(): String = "$path: $message"
}

/** Outcome of importing a plan document. */
sealed interface ImportResult {
  /** The plan was validated and written to Room. */
  data class Success(val planId: String, val planName: String, val sessionCount: Int) :
    ImportResult

  /** The document could not be parsed at all (not JSON, truncated, wrong root type). */
  data class Unreadable(val message: String) : ImportResult

  /** The document parsed but broke the schema. Nothing was written. */
  data class Invalid(val errors: List<ImportError>) : ImportResult

  /** Byte-for-byte the same plan is already stored. Nothing was written. */
  data class AlreadyImported(val planId: String, val planName: String) : ImportResult

  /**
   * Nothing was written, because writing would change or discard a plan already in the database.
   * [action] is what the importer would do if the user said yes.
   *
   * This is the only route by which an existing plan is ever touched. Importing used to delete
   * whatever was there without asking whenever the incoming `plan.id` differed, and to refuse
   * outright when it matched — strict about the harmless case and silent about the destructive
   * one.
   */
  data class NeedsConfirmation(val planName: String, val action: PendingImport) : ImportResult

  /**
   * Ids collide with existing rows but the content differs. Nothing was written — replacing an
   * existing plan is the user's decision, never the importer's.
   */
  data class Conflict(val planId: String?, val conflictingSessionIds: List<String>) : ImportResult
}

/** What an import would do to the plan already stored. */
sealed interface PendingImport {

  /**
   * The same programme, corrected.
   *
   * Offered when every session already stored still exists in the incoming document, so nothing
   * has to be thrown away to accept it: each session's content is updated in place and its
   * status, its event history and any reschedule chain hanging off it are left exactly as they
   * were. This is what fixing a typo or adding `guide` references three weeks into a programme
   * actually is.
   *
   * @param changed sessions whose content differs from what is stored.
   * @param added sessions in the document that are not in the database yet.
   */
  data class Update(val changed: Int, val added: Int) : PendingImport

  /**
   * A different programme, or one that has dropped sessions.
   *
   * The stored plan is deleted outright, and its sessions and their events cascade away with it.
   * There is no honest way to carry a status onto a session that no longer exists, so this is
   * offered as the loss it is rather than dressed up as a merge.
   *
   * @param recordedSessions sessions carrying something other than `PLANNED` — what is actually
   *   lost, as opposed to how many rows disappear.
   */
  data class Replace(val replacedPlanName: String, val recordedSessions: Int) : PendingImport
}
