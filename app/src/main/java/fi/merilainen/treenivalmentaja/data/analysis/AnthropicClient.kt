package fi.merilainen.treenivalmentaja.data.analysis

import com.squareup.moshi.JsonAdapter
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Claude, via `POST /v1/messages`.
 *
 * OkHttp and Moshi directly rather than the Anthropic SDK, on ADR-007's reasoning for the Oura
 * client: one endpoint, one request shape, both libraries already in the APK, and an SDK would be a
 * new dependency wrapping a single POST.
 *
 * @param baseUrl overridden in tests.
 */
class AnthropicClient
internal constructor(
  private val apiKeys: AnalysisApiKeySource,
  private val baseUrl: String = BASE_URL,
  private val calls: Call.Factory = AnalysisHttp.defaultCallFactory(),
) : AnalysisClient {

  override suspend fun analyse(prompt: String, model: AnalysisModel): String {
    val key =
      apiKeys.apiKey()?.takeIf { it.isNotBlank() }
        ?: throw AnalysisNotConfiguredException(AnalysisProvider.ANTHROPIC.label)
    val body =
      requestAdapter.toJson(
        AnthropicRequestDto(
          model = model.id,
          maxTokens = AnalysisHttp.MAX_OUTPUT_TOKENS,
          messages = listOf(AnthropicMessageDto(role = "user", content = prompt)),
        )
      )
    val response =
      AnalysisHttp.post(
        url = "$baseUrl/v1/messages".toHttpUrl(),
        body = body,
        calls = calls,
        // The key goes in `x-api-key`, not `Authorization` — that header is for OAuth tokens here,
        // and sending the key there authenticates as nobody.
        headers = mapOf("x-api-key" to key, "anthropic-version" to API_VERSION),
      )
    return AnalysisHttp.decode(response, responseAdapter).firstText()
  }

  /**
   * The text, from a response whose shape depends on whether the model thinks.
   *
   * Two guards, both with a real failure behind them:
   *
   *  1. **`stop_reason` is read before `content` is touched.** A refusal is a successful `200` whose
   *     content list may be empty, so indexing first would crash on the one response *designed* to
   *     carry nothing.
   *  2. **The first *text* block, not the first block.** Thinking blocks precede text on Sonnet and
   *     Opus and their text is empty; on Haiku, which does not think, block zero is the text.
   *     `content[0].text` would work on one of the three and render blank on the other two — a bug
   *     that shipped once already and is now pinned by a test.
   */
  private fun AnthropicResponseDto.firstText(): String {
    if (stopReason == STOP_REFUSAL) throw AnalysisRefusedException()
    return with(AnalysisHttp) {
      content.orEmpty().filterNotNull().firstOrNull { it.type == BLOCK_TEXT }?.text.orEmptyFailure()
    }
  }

  companion object {

    const val BASE_URL = "https://api.anthropic.com"

    /** A constant of the wire protocol, unrelated to the model. */
    internal const val API_VERSION = "2023-06-01"

    internal const val BLOCK_TEXT = "text"

    internal const val STOP_REFUSAL = "refusal"

    private val requestAdapter: JsonAdapter<AnthropicRequestDto> =
      AnalysisHttp.moshi.adapter(AnthropicRequestDto::class.java)

    private val responseAdapter: JsonAdapter<AnthropicResponseDto> =
      AnalysisHttp.moshi.adapter(AnthropicResponseDto::class.java)
  }
}
