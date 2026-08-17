package fi.merilainen.treenivalmentaja.data.anthropic

import com.squareup.moshi.Json

/**
 * The request body for `POST /v1/messages`.
 *
 * Deliberately the minimum the endpoint accepts. **No `thinking` field is sent**, and that is what
 * lets one body serve all three offered models: Sonnet 5 and Opus 5 think adaptively when the field
 * is absent, Haiku 4.5 does not think at all, and all three accept the request as written. Sending
 * an explicit thinking configuration would have to differ per model and could be rejected outright.
 *
 * No `system` field either — the whole instruction is in the single user message, which is what the
 * "Näytä pyyntö" panel shows. Splitting it would mean the panel showed half the request.
 */
internal data class AnthropicRequestDto(
  val model: String,
  @Json(name = "max_tokens") val maxTokens: Int,
  val messages: List<AnthropicMessageDto>,
)

internal data class AnthropicMessageDto(val role: String, val content: String)

/**
 * The response.
 *
 * Only three fields are read. [stopReason] is checked **before** [content] is walked, because a
 * refusal is a `200` whose content list can be empty.
 */
internal data class AnthropicResponseDto(
  val content: List<AnthropicContentBlockDto?>? = null,
  @Json(name = "stop_reason") val stopReason: String? = null,
  /** Which model actually produced the message. Not displayed; useful if this ever needs debugging. */
  val model: String? = null,
)

/**
 * One content block.
 *
 * **The answer is not necessarily the first block.** `content` is a list of typed blocks, and on the
 * two models that think, the thinking blocks come *first* — so block zero is a `thinking` block
 * whose text is empty (the API omits reasoning text unless asked for it, which this app does not
 * want). On Haiku 4.5, which does not think, block zero *is* the text. Reading index zero would
 * therefore work on one of the three offered models and silently render an empty analysis on the
 * other two, which is why [AnthropicClient] scans for [type] `"text"` instead.
 *
 * One nullable-everything class rather than a sealed hierarchy: the only block this app has any use
 * for is the text one, and a `thinking` block is adequately modelled here as "a block that is not
 * text". Moshi ignores the fields it does not know about.
 */
internal data class AnthropicContentBlockDto(
  val type: String? = null,
  val text: String? = null,
)
