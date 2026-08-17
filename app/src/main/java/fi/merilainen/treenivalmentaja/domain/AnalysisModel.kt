package fi.merilainen.treenivalmentaja.domain

/** Whose API an analysis is asked from. Each has its own key, stored separately. */
enum class AnalysisProvider(val label: String) {
  ANTHROPIC("Claude"),
  OPENAI("ChatGPT"),
  GEMINI("Gemini"),
}

/**
 * The models an analysis may be asked for, across all three providers.
 *
 * **A fixed list rather than a text field**, which is the decision this type exists to hold. A
 * mistyped model id is a `404` discovered at tap time, on a phone, and indistinguishable from a
 * broken key by anyone not reading the source. A list cannot produce that. The price is that it
 * goes stale as models are released — accepted, because it is one enum in one file, and a stale
 * list still works where a typo does not.
 *
 * [id] strings are exact and carry **no date suffix**; appending one produces a `404`.
 *
 * The prices in [detail] are per analysis, at roughly 1 500 tokens in and a page of Finnish out —
 * they are there so the choice can be made without leaving the screen, and they are rounded hard
 * because the point is the order of magnitude, not the third decimal. Nothing computes with them.
 *
 * **Why one enum rather than one per provider.** The stored preference is a single string, the
 * Settings list is a single list, and the ViewModel picks a client by [provider]. Three enums would
 * mean three of each and a sealed wrapper to unify them, for no gain.
 */
enum class AnalysisModel(
  val provider: AnalysisProvider,
  val id: String,
  val label: String,
  val detail: String,
) {
  // ---------------------------------------------------------------- Anthropic

  CLAUDE_HAIKU(
    provider = AnalysisProvider.ANTHROPIC,
    id = "claude-haiku-4-5",
    label = "Claude Haiku 4.5",
    detail = "Nopein, ei erillistä päättelyä · ~0,5 senttiä",
  ),
  CLAUDE_SONNET(
    provider = AnalysisProvider.ANTHROPIC,
    id = "claude-sonnet-5",
    label = "Claude Sonnet 5",
    detail = "Tasapainoinen · ~2–3 senttiä",
  ),
  CLAUDE_OPUS(
    provider = AnalysisProvider.ANTHROPIC,
    id = "claude-opus-5",
    label = "Claude Opus 5",
    detail = "Vahvin päättely · ~5 senttiä",
  ),

  // ---------------------------------------------------------------- OpenAI

  GPT_LUNA(
    provider = AnalysisProvider.OPENAI,
    id = "gpt-5.6-luna",
    label = "ChatGPT 5.6 Luna",
    detail = "Halvin kolmesta · ~0,3 senttiä",
  ),
  GPT_TERRA(
    provider = AnalysisProvider.OPENAI,
    id = "gpt-5.6-terra",
    label = "ChatGPT 5.6 Terra",
    detail = "Tasapainoinen · ~3 senttiä",
  ),
  GPT_SOL(
    provider = AnalysisProvider.OPENAI,
    id = "gpt-5.6-sol",
    label = "ChatGPT 5.6 Sol",
    detail = "Vahvin päättely · ~7 senttiä",
  ),

  // ---------------------------------------------------------------- Google

  /**
   * One Gemini option, not three.
   *
   * Flash is the tier that makes sense here — the analysis is a page of Finnish over labelled
   * numbers, not long-horizon reasoning — and the alternatives were either retiring
   * (`gemini-2.5-flash-lite` shuts down in October 2026) or priced above the Claude and OpenAI
   * options already listed. Offering one is not a limitation; it is the list not being padded.
   */
  GEMINI_FLASH(
    provider = AnalysisProvider.GEMINI,
    id = "gemini-3.7-flash",
    label = "Gemini 3.7 Flash",
    detail = "Halpa ja nopea · ~1 sentti",
  );

  companion object {

    /**
     * Sonnet, because it is the one whose Finnish was checked by hand against the others before
     * this list existed. Changing the default is a one-line change and a matter of taste.
     */
    val DEFAULT: AnalysisModel = CLAUDE_SONNET

    /**
     * The stored preference back into an enum, by [name] rather than [id].
     *
     * The enum constant is the stable identifier: a model id can change when a provider renames or
     * retires one, and a stored preference should survive that rather than silently resetting.
     *
     * Anything unrecognised falls back to [DEFAULT] instead of throwing — the stored value is
     * whatever a previous build wrote, and an option dropped from this list must not stop Settings
     * from drawing. The user simply finds the default selected again.
     */
    fun fromStored(stored: String?): AnalysisModel =
      entries.firstOrNull { it.name == stored } ?: DEFAULT

    /** Grouped for the Settings list, in declaration order within each provider. */
    fun byProvider(): Map<AnalysisProvider, List<AnalysisModel>> = entries.groupBy { it.provider }
  }
}
