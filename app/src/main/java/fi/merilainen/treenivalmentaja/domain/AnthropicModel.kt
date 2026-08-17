package fi.merilainen.treenivalmentaja.domain

/**
 * The models the analysis may be asked for, and what each costs.
 *
 * **A fixed list rather than a text field**, which is the whole decision here. A mistyped model id
 * is a `404` discovered at tap time, on a phone, and indistinguishable from a broken key by anyone
 * who is not reading the source. Three known-good ids in a dropdown cannot produce that. The price
 * is that this list goes stale as models are released — accepted, because it is one enum in one file
 * and a stale list still works where a typo does not.
 *
 * [id] is the exact model string. It carries **no date suffix**: these ids are complete as written,
 * and appending a date produces a `404`.
 *
 * The per-token prices are in the labels rather than in code because nothing computes with them —
 * they are there so the choice can be made without leaving the screen.
 */
enum class AnthropicModel(
  val id: String,
  /** What the option is called in Settings. */
  val label: String,
  /** One line under the label: what the choice actually buys. */
  val detail: String,
) {
  /**
   * The cheapest, and the only one of the three that does not think — which is why its answers
   * arrive fastest and cost least, and why they are the shallowest.
   */
  HAIKU(
    id = "claude-haiku-4-5",
    label = "Nopein ja edullisin",
    detail = "Haiku 4.5 · noin puoli senttiä per analyysi",
  ),

  /**
   * The default. The task is interpreting a page of labelled numbers against explicit bands, which
   * is judgment over structured input rather than open-ended reasoning.
   */
  SONNET(
    id = "claude-sonnet-5",
    label = "Tasapainoinen (oletus)",
    detail = "Sonnet 5 · noin 2–3 senttiä per analyysi",
  ),

  /** The most capable, and the most expensive. Worth trying against a real week before deciding. */
  OPUS(
    id = "claude-opus-5",
    label = "Paras arvio, kallein",
    detail = "Opus 5 · noin 4–6 senttiä per analyysi",
  );

  companion object {

    val DEFAULT: AnthropicModel = SONNET

    /**
     * The stored preference back into an enum.
     *
     * An unknown id falls back to [DEFAULT] rather than throwing: the stored value is whatever was
     * chosen under a previous build, and a model dropped from this list must not stop the app from
     * starting. The user simply finds the default selected again.
     */
    fun fromId(id: String?): AnthropicModel = entries.firstOrNull { it.id == id } ?: DEFAULT
  }
}
