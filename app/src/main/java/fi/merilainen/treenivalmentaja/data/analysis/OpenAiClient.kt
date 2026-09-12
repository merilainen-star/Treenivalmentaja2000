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
  private val calls: Call.Factory? = null,
) : AnalysisClient {

  override suspend fun analyse(prompt: String, model: AnalysisModel, task: AnalysisTask): String {
    val key =
      apiKeys.apiKey()?.takeIf { it.isNotBlank() }
        ?: throw AnalysisNotConfiguredException(AnalysisProvider.OPENAI.label)
    val body =
      requestAdapter.toJson(
        OpenAiRequestDto(
          model = model.id,
          // `max_completion_tokens`, not `max_tokens`: the older field is deprecated and is
          // rejected outright by the reasoning-capable models, which is every model offered here.
          maxCompletionTokens = task.maxOutputTokens,
          messages = listOf(OpenAiMessageDto(role = "user", content = prompt)),
        )
      )
    val response =
      AnalysisHttp.post(
        url = "$baseUrl/v1/chat/completions".toHttpUrl(),
        body = body,
        calls = calls ?: AnalysisHttp.defaultCallFactory(task),
        headers = mapOf("Authorization" to "Bearer $key"),
      )
    return AnalysisHttp.decode(response, responseAdapter).firstText(task)
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
   * Partial prose remains readable. Programme JSON must be complete, and an empty answer
   * at the ceiling is a token-limit failure even when all tokens went into hidden reasoning.
   */
  private fun OpenAiResponseDto.firstText(task: AnalysisTask): String {
    val choice = choices.orEmpty().filterNotNull().firstOrNull()
    if (choice?.finishReason == FINISH_CONTENT_FILTER) throw AnalysisRefusedException()
    if (choice?.finishReason == "length" &&
      (task == AnalysisTask.PROGRAM || choice.message?.content.isNullOrBlank())) {
      throw AnalysisOutputLimitException()
    }
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
