package fi.merilainen.treenivalmentaja.data.analysis

import com.squareup.moshi.Json

// ====================================================================== Anthropic

/**
 * `POST /v1/messages`.
 *
 * **No `thinking` field is sent**, and that is what lets one body serve all three Claude models:
 * Sonnet 5 and Opus 5 think adaptively when it is absent, Haiku 4.5 does not think at all, and all
 * three accept the request as written.
 */
internal data class AnthropicRequestDto(
  val model: String,
  @Json(name = "max_tokens") val maxTokens: Int,
  val messages: List<AnthropicMessageDto>,
)

internal data class AnthropicMessageDto(val role: String, val content: String)

internal data class AnthropicResponseDto(
  val content: List<AnthropicContentBlockDto?>? = null,
  @Json(name = "stop_reason") val stopReason: String? = null,
)

/**
 * One content block.
 *
 * **The answer is not necessarily the first one.** On the Claude models that think, thinking blocks
 * come *first* and their text is empty, so `content[0].text` would be `""`. On Haiku, which does not
 * think, block zero *is* the text. See [AnthropicClient].
 */
internal data class AnthropicContentBlockDto(
  val type: String? = null,
  val text: String? = null,
)

// ====================================================================== OpenAI

/**
 * `POST /v1/chat/completions`.
 *
 * **`max_completion_tokens`, not `max_tokens`.** The older field is deprecated and is rejected
 * outright by the reasoning-capable models; the newer one bounds visible output *and* hidden
 * reasoning tokens together, which is the same shape Claude's `max_tokens` has.
 */
internal data class OpenAiRequestDto(
  val model: String,
  @Json(name = "max_completion_tokens") val maxCompletionTokens: Int,
  val messages: List<OpenAiMessageDto>,
)

internal data class OpenAiMessageDto(val role: String, val content: String)

internal data class OpenAiResponseDto(val choices: List<OpenAiChoiceDto?>? = null)

/**
 * One choice.
 *
 * [finishReason] is `stop` on a normal answer, `length` when the token ceiling was hit, and
 * `content_filter` when the answer was suppressed — the last of which arrives as a `200` with
 * `message.content` null or empty, so it has to be checked rather than parsed around.
 */
internal data class OpenAiChoiceDto(
  val message: OpenAiResponseMessageDto? = null,
  @Json(name = "finish_reason") val finishReason: String? = null,
)

internal data class OpenAiResponseMessageDto(val content: String? = null)

// ====================================================================== Gemini

/**
 * `POST /v1beta/models/{model}:generateContent`.
 *
 * A different shape from the other two: no roles, no message list — just parts of content, with the
 * output ceiling nested inside `generationConfig` rather than sitting at the top level.
 */
internal data class GeminiRequestDto(
  val contents: List<GeminiContentDto>,
  val generationConfig: GeminiGenerationConfigDto,
)

internal data class GeminiContentDto(val parts: List<GeminiPartDto>)

internal data class GeminiPartDto(val text: String? = null)

internal data class GeminiGenerationConfigDto(val maxOutputTokens: Int)

/**
 * The response.
 *
 * **[candidates] can be absent entirely.** When a prompt is blocked before generation, Gemini
 * returns a `200` carrying only [promptFeedback] with a `blockReason` and no candidates at all —
 * the one shape where reaching for `candidates[0]` throws rather than merely reading empty.
 */
internal data class GeminiResponseDto(
  val candidates: List<GeminiCandidateDto?>? = null,
  val promptFeedback: GeminiPromptFeedbackDto? = null,
)

/**
 * One candidate.
 *
 * [finishReason] is `STOP` on a normal answer, `MAX_TOKENS` at the ceiling, and `SAFETY` when the
 * *output* was filtered — the last of which leaves [content] empty on an otherwise successful
 * response.
 */
internal data class GeminiCandidateDto(
  val content: GeminiContentResponseDto? = null,
  val finishReason: String? = null,
)

internal data class GeminiContentResponseDto(val parts: List<GeminiPartDto?>? = null)

internal data class GeminiPromptFeedbackDto(val blockReason: String? = null)
