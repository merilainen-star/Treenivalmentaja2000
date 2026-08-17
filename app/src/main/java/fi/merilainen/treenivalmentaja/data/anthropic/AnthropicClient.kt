package fi.merilainen.treenivalmentaja.data.anthropic

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import fi.merilainen.treenivalmentaja.domain.AnthropicModel
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Asks Claude for one analysis, and returns the prose it wrote.
 *
 * OkHttp and Moshi directly rather than the Anthropic SDK, on ADR-007's reasoning for the Oura
 * client: one endpoint, one request shape, both libraries already in the APK, and an SDK would be a
 * new dependency wrapping a single POST. See ADR-010 in `docs/DECISIONS.md`.
 *
 * **A public class with an internal constructor**, the shape `IntervalsRepository` already uses: the
 * ViewModel's constructor is public and has to name this type, but nothing outside this module has
 * any business building one — its DTOs are internal and its collaborators are wired in
 * `TreenivalmentajaApplication`. What leaves this package is a `String` of Finnish prose, or a typed
 * [AnthropicException] whose message is already Finnish.
 *
 * Nothing here decides *what* to ask — the prompt arrives fully built from
 * `domain/AnalysisPromptBuilder.kt`, which is a pure function and therefore the part that is worth
 * unit-testing. This half is transport.
 *
 * @param baseUrl overridden in tests.
 */
class AnthropicClient internal constructor(
  private val apiKeys: AnthropicApiKeySource,
  private val baseUrl: String = BASE_URL,
  private val calls: Call.Factory = defaultCallFactory(),
) {

  /**
   * One prompt in, one answer out.
   *
   * @param model which of the offered models to spend. The user's choice, read fresh per call so
   *   changing it in Settings takes effect on the next tap rather than the next launch.
   * @return the model's text, trimmed. Never empty — an answer with no text block is
   *   [AnthropicUnavailableException], because rendering an empty card would look like success.
   */
  suspend fun analyse(prompt: String, model: AnthropicModel): String {
    val key = apiKeys.apiKey()?.takeIf { it.isNotBlank() } ?: throw AnthropicNotConfiguredException()
    val body =
      requestAdapter.toJson(
        AnthropicRequestDto(
          model = model.id,
          maxTokens = MAX_TOKENS,
          messages = listOf(AnthropicMessageDto(role = "user", content = prompt)),
        )
      )
    return decode(post(body, key)).firstText()
  }

  /**
   * The text of the answer, from a response whose shape depends on whether the model thinks.
   *
   * Two guards, and both have a real failure behind them rather than being defensive habit:
   *
   *  1. **`stop_reason` is read before `content` is touched.** A refusal is a successful `200` whose
   *     content list may be empty, so indexing first would crash on the one response that is
   *     *designed* to carry nothing.
   *  2. **The first *text* block, not the first block.** Thinking blocks precede text on the models
   *     that think, and their text is empty. `content[0].text` would work on Haiku and render blank
   *     on Sonnet and Opus.
   */
  private fun AnthropicResponseDto.firstText(): String {
    if (stopReason == STOP_REFUSAL) throw AnthropicRefusedException()
    val text =
      content
        .orEmpty()
        .filterNotNull()
        .firstOrNull { it.type == BLOCK_TEXT }
        ?.text
        ?.trim()
    return text?.takeIf { it.isNotEmpty() } ?: throw AnthropicUnavailableException(EMPTY)
  }

  private fun decode(body: String): AnthropicResponseDto =
    try {
      responseAdapter.fromJson(body)
    } catch (e: JsonEncodingException) {
      // What a non-JSON body — a proxy's error page, say — lands on.
      throw AnthropicUnavailableException(UNREADABLE)
    } catch (e: JsonDataException) {
      throw AnthropicUnavailableException(UNREADABLE)
    } catch (e: IOException) {
      throw AnthropicUnavailableException(UNREADABLE)
    } ?: throw AnthropicUnavailableException(UNREADABLE)

  /**
   * One POST, and the same reading of what went wrong every time.
   *
   * The status is checked before the body is trusted, because a failing service is under no
   * obligation to answer in JSON.
   */
  private suspend fun post(json: String, key: String): String =
    withContext(Dispatchers.IO) {
      val request =
        Request.Builder()
          .url("$baseUrl/v1/messages")
          // The key goes in `x-api-key`, not `Authorization` — that header is for OAuth tokens, and
          // sending the key there authenticates as nobody.
          .header("x-api-key", key)
          .header("anthropic-version", API_VERSION)
          .header("Accept", "application/json")
          .post(json.toRequestBody(JSON_MEDIA_TYPE))
          .build()
      val response =
        try {
          calls.newCall(request).execute()
        } catch (e: IOException) {
          throw AnthropicUnavailableException(OFFLINE)
        }
      response.use {
        when (val code = it.code) {
          200 -> Unit
          401 -> throw AnthropicAuthException()
          // A key that exists but is not permitted reads the same to the user as one that is wrong.
          403 -> throw AnthropicAuthException()
          404 -> throw AnthropicModelGoneException()
          429 ->
            throw AnthropicRateLimitException(
              it.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { s -> s > 0 }
            )
          529 -> throw AnthropicOverloadedException()
          400, 422 -> throw AnthropicRequestException(code)
          else -> throw AnthropicUnavailableException("Palvelu vastasi HTTP $code.")
        }
        try {
          it.body?.string() ?: throw AnthropicUnavailableException(UNREADABLE)
        } catch (e: IOException) {
          throw AnthropicUnavailableException(OFFLINE)
        }
      }
    }

  companion object {

    const val BASE_URL = "https://api.anthropic.com"

    /** The API version header. A constant of the wire protocol, unrelated to the model. */
    internal const val API_VERSION = "2023-06-01"

    /**
     * The output ceiling — **thinking and visible text together**.
     *
     * Far above the few hundred tokens the Finnish answer needs, because on the two thinking models
     * this bounds the reasoning as well: a budget sized for the prose alone would be spent on
     * thinking and truncate the answer mid-sentence. A ceiling costs nothing unless it is reached;
     * only tokens actually generated are billed.
     */
    internal const val MAX_TOKENS = 8192

    internal const val BLOCK_TEXT = "text"

    internal const val STOP_REFUSAL = "refusal"

    internal const val OFFLINE = "AI-analyysi vaatii verkkoyhteyden."

    internal const val UNREADABLE = "Vastausta ei voitu lukea."

    internal const val EMPTY = "Vastaus tuli tyhjänä."

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val requestAdapter: JsonAdapter<AnthropicRequestDto> =
      moshi.adapter(AnthropicRequestDto::class.java)

    private val responseAdapter: JsonAdapter<AnthropicResponseDto> =
      moshi.adapter(AnthropicResponseDto::class.java)

    /**
     * Longer than the other clients' ten seconds, and that is the point of a separate factory: a
     * thinking model on a hard analysis can take the better part of a minute, where an Oura fetch
     * that has not answered in ten seconds is broken.
     */
    private fun defaultCallFactory(): Call.Factory =
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
  }
}
