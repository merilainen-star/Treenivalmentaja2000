package fi.merilainen.treenivalmentaja.data.analysis

import com.squareup.moshi.JsonAdapter
import fi.merilainen.treenivalmentaja.domain.AnalysisModel
import fi.merilainen.treenivalmentaja.domain.AnalysisProvider
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Gemini, via `POST /v1beta/models/{model}:generateContent`.
 *
 * Three things differ from the other two clients, and all three are the provider's shape rather than
 * a choice made here.
 *
 * **The key travels in a header, not the query string.** Google's own documentation shows
 * `?key=YOUR_API_KEY`, and it still works — but a secret in a URL is a secret in every proxy log,
 * every crash report and every `Referer` along the way. `x-goog-api-key` is the form Google's
 * current guidance recommends and the only one used here. The model id *is* in the path, which is
 * fine: it is not a secret.
 *
 * **The model id is part of the URL**, not a body field, so an unknown model is a `404` on a path
 * rather than a rejected parameter — which happens to map onto the same `AnalysisModelGoneException`
 * the other two produce.
 *
 * **A rejected key answers `400`, not `401`.** Left alone that would surface as "pyyntö hylättiin
 * (HTTP 400)" — telling the owner their app is broken when their key is merely wrong. Hence
 * `authOn400`.
 *
 * @param baseUrl overridden in tests.
 */
class GeminiClient
internal constructor(
  private val apiKeys: AnalysisApiKeySource,
  private val baseUrl: String = BASE_URL,
  private val calls: Call.Factory = AnalysisHttp.defaultCallFactory(),
) : AnalysisClient {

  override suspend fun analyse(prompt: String, model: AnalysisModel): String {
    val key =
      apiKeys.apiKey()?.takeIf { it.isNotBlank() }
        ?: throw AnalysisNotConfiguredException(AnalysisProvider.GEMINI.label)
    val body =
      requestAdapter.toJson(
        GeminiRequestDto(
          contents = listOf(GeminiContentDto(parts = listOf(GeminiPartDto(text = prompt)))),
          generationConfig =
            GeminiGenerationConfigDto(maxOutputTokens = AnalysisHttp.MAX_OUTPUT_TOKENS),
        )
      )
    val response =
      AnalysisHttp.post(
        url = "$baseUrl/v1beta/models/${model.id}:generateContent".toHttpUrl(),
        body = body,
        calls = calls,
        headers = mapOf("x-goog-api-key" to key),
        authOn400 = true,
      )
    return AnalysisHttp.decode(response, responseAdapter).firstText()
  }

  /**
   * The text, from the first candidate's first part.
   *
   * Gemini has **two** distinct ways of returning nothing on a `200`, and they are not the same
   * event:
   *
   *  1. **The prompt was blocked before generation.** `promptFeedback.blockReason` is set and
   *     `candidates` is **absent entirely** — the one shape in any of the three providers where
   *     reaching for element zero throws rather than reading empty. Checked first, for that reason.
   *  2. **The output was filtered after generation.** A candidate exists with
   *     `finishReason: "SAFETY"` and no usable parts.
   *
   * Both are reported as [AnalysisRefusedException] — the distinction matters to this comment, not
   * to someone looking at a training card.
   */
  private fun GeminiResponseDto.firstText(): String {
    if (promptFeedback?.blockReason != null) throw AnalysisRefusedException()
    val candidate = candidates.orEmpty().filterNotNull().firstOrNull()
    if (candidate?.finishReason == FINISH_SAFETY) throw AnalysisRefusedException()
    // Parts are joined rather than taking the first: a long answer can arrive split across several,
    // and reading only part one would silently truncate it.
    val text =
      candidate
        ?.content
        ?.parts
        .orEmpty()
        .filterNotNull()
        .mapNotNull { it.text }
        .joinToString("")
    return with(AnalysisHttp) { text.orEmptyFailure() }
  }

  companion object {

    const val BASE_URL = "https://generativelanguage.googleapis.com"

    internal const val FINISH_SAFETY = "SAFETY"

    private val requestAdapter: JsonAdapter<GeminiRequestDto> =
      AnalysisHttp.moshi.adapter(GeminiRequestDto::class.java)

    private val responseAdapter: JsonAdapter<GeminiResponseDto> =
      AnalysisHttp.moshi.adapter(GeminiResponseDto::class.java)
  }
}
