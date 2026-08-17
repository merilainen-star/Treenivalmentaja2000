package fi.merilainen.treenivalmentaja.data.analysis

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The transport every analysis client shares: one POST, one reading of what went wrong.
 *
 * Extracted because the three providers differ in their *payloads*, not in how a failed HTTP call
 * should be reported. Without this, each client would map `401` to a Finnish string of its own and
 * the three would drift — which is how the same failure ends up phrased three ways on one screen.
 *
 * What each client still owns: its URL, its auth header, its request body, and where the answer
 * hides in the response. Those are genuinely different and are not abstracted away.
 */
internal object AnalysisHttp {

  /**
   * Longer than the ten seconds every other caller in this app uses.
   *
   * A reasoning model on a hard analysis can take the better part of a minute, where an Oura fetch
   * that has not answered in ten seconds is broken. The connect timeout stays short because failing
   * to *reach* the host is a different thing from waiting for it to think.
   */
  fun defaultCallFactory(): Call.Factory =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .build()

  val JSON: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()

  val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

  /**
   * One POST, with the status checked before the body is trusted — a failing service is under no
   * obligation to answer in JSON.
   *
   * The status mapping is shared because all three providers use the same HTTP vocabulary for these:
   * `401`/`403` a rejected key, `404` a model that is gone, `429` rate limiting, `5xx` trouble at
   * their end. Where they differ is *inside* a `200`, which is each client's own problem.
   *
   * **`400` is the one that needs care and is why it is not simply "our bug".** Gemini answers `400`
   * for an invalid API key where the other two answer `401`, so a blanket "pyyntö hylättiin" would
   * tell the owner their app is broken when their key is merely wrong. [authOn400] lets the Gemini
   * client opt into reading it as an auth failure.
   */
  suspend fun post(
    url: HttpUrl,
    body: String,
    calls: Call.Factory,
    headers: Map<String, String>,
    authOn400: Boolean = false,
  ): String =
    withContext(Dispatchers.IO) {
      val request =
        Request.Builder()
          .url(url)
          .apply { headers.forEach { (name, value) -> header(name, value) } }
          .header("Accept", "application/json")
          .post(body.toRequestBody())
          .build()
      val response =
        try {
          calls.newCall(request).execute()
        } catch (e: IOException) {
          throw AnalysisUnavailableException(AnalysisMessages.OFFLINE)
        }
      response.use {
        when (val code = it.code) {
          200 -> Unit
          400 -> if (authOn400) throw AnalysisAuthException() else throw AnalysisRequestException(400)
          401, 403 -> throw AnalysisAuthException()
          404 -> throw AnalysisModelGoneException()
          422 -> throw AnalysisRequestException(422)
          429 ->
            throw AnalysisRateLimitException(
              it.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { s -> s > 0 }
            )
          // 529 is Anthropic's own overload code; 500..504 covers the other two.
          529, in 500..504 -> throw AnalysisOverloadedException()
          else -> throw AnalysisUnavailableException("Palvelu vastasi HTTP $code.")
        }
        try {
          it.body?.string() ?: throw AnalysisUnavailableException(AnalysisMessages.UNREADABLE)
        } catch (e: IOException) {
          throw AnalysisUnavailableException(AnalysisMessages.OFFLINE)
        }
      }
    }

  /** Every provider's body lands here, so an unreadable one reads the same way once. */
  fun <T : Any> decode(body: String, adapter: JsonAdapter<T>): T =
    try {
      adapter.fromJson(body)
    } catch (e: JsonEncodingException) {
      // What a non-JSON body — a proxy's error page, say — lands on.
      throw AnalysisUnavailableException(AnalysisMessages.UNREADABLE)
    } catch (e: JsonDataException) {
      throw AnalysisUnavailableException(AnalysisMessages.UNREADABLE)
    } catch (e: IOException) {
      throw AnalysisUnavailableException(AnalysisMessages.UNREADABLE)
    } ?: throw AnalysisUnavailableException(AnalysisMessages.UNREADABLE)

  /** Non-empty text, or the honest failure — an empty card would otherwise look like success. */
  fun String?.orEmptyFailure(): String =
    this?.trim()?.takeIf { it.isNotEmpty() }
      ?: throw AnalysisUnavailableException(AnalysisMessages.EMPTY)

  private fun String.toRequestBody() =
    okhttp3.RequestBody.create(JSON, this.toByteArray(Charsets.UTF_8))

  /**
   * The output ceiling, shared by all three.
   *
   * Far above the ~110 words the Finnish answer is now asked for, because on the reasoning models
   * this bounds hidden thinking as well: a budget sized for the prose alone would be spent on
   * reasoning and truncate the answer mid-sentence. A ceiling costs nothing unless it is reached;
   * only tokens actually generated are billed.
   */
  const val MAX_OUTPUT_TOKENS = 8192
}
