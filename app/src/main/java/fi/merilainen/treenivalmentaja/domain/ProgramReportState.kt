package fi.merilainen.treenivalmentaja.domain

/**
 * What the whole-programme report card is showing.
 *
 * One value for the screen rather than a map keyed by anything: there is one active plan, and only
 * one report about it can be open at a time. That is the difference from `AiAnalysisState`, which is
 * held per session because a week list can have several cards open at once.
 *
 * Like `AiAnalysisState`, nothing here is written to the database **yet** — the report table is a
 * later slice of `docs/PROGRAM_ANALYSIS.md`, and until it lands a report lives as long as the
 * ViewModel does. Unlike `AiAnalysisState`, that is a limitation rather than a design: a final
 * report is a document about a period that is closed and cannot change, and re-asking for it costs
 * a request and returns the same thing.
 */
sealed interface ProgramReportState {

  /** The request is in flight. [kind] so the spinner can say which report is being written. */
  data class Loading(val kind: ProgramReportKind) : ProgramReportState

  /**
   * A report arrived.
   *
   * @param text the model's prose, shown as written.
   * @param prompt exactly what was sent, for the "Näytä pyyntö" panel — kept beside the answer for
   *   the reason the session analysis keeps it: the plan may change afterwards, and a panel showing
   *   a *reconstruction* of the request would be showing something that was never sent.
   */
  data class Loaded(val kind: ProgramReportKind, val text: String, val prompt: String) :
    ProgramReportState

  /** Something went wrong. [message] is already Finnish; [canRetry] decides whether to offer it. */
  data class Failed(val kind: ProgramReportKind, val message: String, val canRetry: Boolean) :
    ProgramReportState

  /**
   * There is no plan, or nothing has been done in it yet.
   *
   * A distinct state rather than a failure, because it is not one: a report cannot be written about
   * a programme with no completed sessions, and saying so is the correct outcome of asking.
   */
  data class NotEnoughData(val message: String) : ProgramReportState
}

/**
 * Which report a plan can produce right now, or `null` when it can produce none.
 *
 * A pure function of the analysis, and separate from the card for the reason every rule in this
 * project is: the windows are a decision, and a decision belongs somewhere it can be tested.
 *
 * A finished programme offers the final report; an unfinished one offers the interim. The interim
 * needs something to report on — [MINIMUM_SESSIONS_FOR_INTERIM] completed sessions — because a
 * report after one workout is a report about one workout, which the per-session analysis already
 * does better.
 */
fun availableProgramReport(analysis: TrainingProgramAnalysis?): ProgramReportKind? {
  if (analysis == null) return null
  return when (analysis.phase) {
    ProgramPhase.Finished -> ProgramReportKind.FINAL.takeIf { analysis.adherence.completed > 0 }
    is ProgramPhase.InProgress ->
      ProgramReportKind.INTERIM.takeIf { analysis.adherence.completed >= MINIMUM_SESSIONS_FOR_INTERIM }
  }
}

/** Three: enough for a shape, and few enough that a plan is not silent for a month. */
const val MINIMUM_SESSIONS_FOR_INTERIM = 3

/**
 * One piece of a report as it should be drawn.
 *
 * The report's structure — fact, then reading, then advice — is the point of the whole feature, so
 * the card renders the model's headings as headings. Without this it printed them as literal `##`,
 * which turns the one thing the report is organised around into punctuation.
 */
sealed interface ProgramReportBlock {
  data class Heading(val text: String) : ProgramReportBlock

  data class Paragraph(val text: String) : ProgramReportBlock
}

/**
 * Splits a report into headings and paragraphs.
 *
 * **Deliberately not a Markdown parser.** The prompt asks for a fixed set of `##` headings and
 * prose between them, so the only syntax that can appear is the only syntax handled here. A real
 * parser would pull in a dependency to interpret emphasis and lists the report never contains, and
 * would still have to decide what to do with the one construct that matters.
 *
 * Anything that is not a heading is prose, including a stray `###` a model might write — it is
 * still a heading in intent, and treating it as body text would hide a section break. Blank lines
 * separate paragraphs; runs of them collapse.
 */
fun programReportBlocks(text: String): List<ProgramReportBlock> {
  val blocks = mutableListOf<ProgramReportBlock>()
  val paragraph = StringBuilder()

  fun flush() {
    val body = paragraph.toString().trim()
    if (body.isNotEmpty()) blocks += ProgramReportBlock.Paragraph(body)
    paragraph.setLength(0)
  }

  text.lines().forEach { raw ->
    val line = raw.trim()
    when {
      line.startsWith("#") -> {
        flush()
        val heading = line.trimStart('#').trim()
        if (heading.isNotEmpty()) blocks += ProgramReportBlock.Heading(heading)
      }
      line.isEmpty() -> flush()
      else -> {
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
      }
    }
  }
  flush()
  return blocks
}
