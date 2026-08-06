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
   * Ids collide with existing rows but the content differs. Nothing was written — replacing an
   * existing plan is the user's decision, never the importer's.
   */
  data class Conflict(val planId: String?, val conflictingSessionIds: List<String>) : ImportResult
}
