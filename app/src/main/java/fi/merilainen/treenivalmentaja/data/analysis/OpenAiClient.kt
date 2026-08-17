package fi.merilainen.treenivalmentaja.data.analysis

import com.squareup.moshi.JsonAdapter
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * ChatGPT, via `POST /v1/chat/completions`.
 *
 * @param baseUrl overridden in tests.
 */
class OpenAiClient
internal constructor(
  private val apiKeys: AnalysisApiKeySource,
  private val baseUrl: String = BASE_URL,
  private val calls: Call.Factory = AnalysisHttp.defaultCallFactory(),
) : AnalysisClient {

  override suspend fun analyse(prompt: String, model: AnalysisModel): String {
    val key =
      apiKeys.apiKey()?.takeIf { it.isNotBlank() }
        ?: throw AnalysisNotConfiguredException(AnalysisProvider.OPENAI.label)
    val body =
      requestAdapter.toJson(
        OpenAiRequestDto(
          model = model.id,
          // `max_completion_tokens`, not `max_tokens`: the older field is deprecated and is
          // rejected outright by the reasoning-capable models, which is every model offered here.
          maxCompletionTokens = AnalysisHttp.MAX_OUTPUT_TOKENS,
          messages = listOf(OpenAiMessageDto(role = "user", content = prompt)),
        )
      )
    val response =
      AnalysisHttp.post(
        url = "$baseUrl/v1/chat/completions".toHttpUrl(),
        body = body,
        calls = calls,
        headers = mapOf("Authorization" to "Bearer $key"),
      )
    return AnalysisHttp.decode(response, responseAdapter).firstText()
  }

  /**
   * The text, from the first choice.
   *
   * Simpler than Claude's — there is one place the answer lives — but it has its own trap:
   * **`finish_reason: "content_filter"` arrives as a `200`** whose `message.content` is null or
   * empty. Treating that as "unreadable response" would blame the network for a decision the
   * provider made, so it is checked first and reported as a refusal, the same state Claude's
   * `stop_reason: "refusal"` produces.
   *
   * `length` is deliberately *not* treated as a failure: the ceiling is 8192 tokens against an
   * answer asked to be 110 words, so hitting it means the model spent the budget reasoning and
   * still produced prose. Truncated prose is worth showing; an exception would throw it away.
   */
  private fun OpenAiResponseDto.firstText(): String {
    val choice = choices.orEmpty().filterNotNull().firstOrNull()
    if (choice?.finishReason == FINISH_CONTENT_FILTER) throw AnalysisRefusedException()
    return with(AnalysisHttp) { choice?.message?.content.orEmptyFailure() }
  }

  companion object {

    const val BASE_URL = "https://api.openai.com"

    internal const val FINISH_CONTENT_FILTER = "content_filter"

    private val requestAdapter: JsonAdapter<OpenAiRequestDto> =
      AnalysisHttp.moshi.adapter(OpenAiRequestDto::class.java)

    private val responseAdapter: JsonAdapter<OpenAiResponseDto> =
      AnalysisHttp.moshi.adapter(OpenAiResponseDto::class.java)
  }
}
