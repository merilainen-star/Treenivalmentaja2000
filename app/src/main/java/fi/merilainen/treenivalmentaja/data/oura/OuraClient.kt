package fi.merilainen.treenivalmentaja.data.oura

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads Oura API V2 collections between two dates.
 *
 * OkHttp rather than the plain `HttpURLConnection` the rest of this app's networking uses
 * ([fi.merilainen.treenivalmentaja.data.guide.GuideHttp],
 * [fi.merilainen.treenivalmentaja.data.update.HttpUpdateService]) for one reason: token renewal.
 * `docs/AUTHENTICATION.md` puts the 401 → refresh → retry cycle in an OkHttp `Authenticator`, and
 * serialising concurrent refreshes so a rotated refresh token is not spent twice is the kind of
 * thing worth not writing by hand. OkHttp costs nothing to adopt here — Coil already puts 4.12.0
 * inside the APK, checked by finding `okhttp3/OkHttpClient` in the baseline build's DEX — where
 * Retrofit, which `docs/ROADMAP.md` originally named, would be a new dependency wrapping a single
 * endpoint shape. See ADR-007 in `docs/DECISIONS.md`.
 *
 * `internal` because its DTOs are: nothing outside the data layer should see Oura's field names,
 * and what leaves this package are Room rows built by [OuraMappers].
 *
 * That `Authenticator` is deliberately **not** installed yet: it needs the refresh token and the
 * client secret, which arrive with the OAuth flow in the next milestone step. Until then a `401` is
 * reported as [OuraAuthException] rather than silently retried.
 *
 * Nothing here decides *when* to sync or what to do with the result. It answers "what does Oura say
 * about these days", and fails with a typed, already-Finnish [OuraException] when it cannot.
 *
 * @param baseUrl overridden in tests. Not read from the specification: its `servers[0].url` is
 *   `https://api.None.com`, a placeholder that was never filled in.
 * @param useSandbox routes to `/v2/sandbox/usercollection`, which returns synthetic data for a
 *   ring that has not been worn for a week. It is **not** a way around the credentials — the spec
 *   declares the same token requirement on the sandbox paths.
 */
internal class OuraClient(
  private val tokens: OuraTokenSource,
  private val baseUrl: String = BASE_URL,
  private val useSandbox: Boolean = false,
  private val calls: Call.Factory = defaultCallFactory(),
) {

  /** Readiness scores, one document per day the ring reported anything at all. */
  suspend fun readiness(from: LocalDate, to: LocalDate): List<OuraDailyScoreDto> =
    fetch(OuraCollection.DAILY_READINESS, from, to, dailyScoreAdapter)

  suspend fun sleep(from: LocalDate, to: LocalDate): List<OuraDailyScoreDto> =
    fetch(OuraCollection.DAILY_SLEEP, from, to, dailyScoreAdapter)

  suspend fun activity(from: LocalDate, to: LocalDate): List<OuraDailyScoreDto> =
    fetch(OuraCollection.DAILY_ACTIVITY, from, to, dailyScoreAdapter)

  /** Completed workouts, including ones synced into Oura from Suunto or Strava. */
  suspend fun workouts(from: LocalDate, to: LocalDate): List<OuraWorkoutDto> =
    fetch(OuraCollection.WORKOUT, from, to, workoutAdapter)

  /**
   * One collection, every page of it.
   *
   * Follows `next_token` until the response stops carrying one. The page cap is not a silent
   * truncation: a service that kept handing out tokens forever would otherwise loop until the
   * process died, so the loop gives up loudly instead of quietly returning part of an answer.
   */
  private suspend fun <T : Any> fetch(
    collection: OuraCollection,
    from: LocalDate,
    to: LocalDate,
    adapter: JsonAdapter<OuraPageDto<T>>,
  ): List<T> {
    val token = tokens.accessToken()?.takeIf { it.isNotBlank() } ?: throw OuraNotConnectedException()
    val items = mutableListOf<T>()
    var nextToken: String? = null
    var page = 0
    do {
      if (++page > MAX_PAGES) {
        throw OuraUnavailableException(
          "Oura palautti yli $MAX_PAGES sivua tietoja. Hakua ei viety loppuun."
        )
      }
      val body = get(url(collection, from, to, nextToken), token)
      val decoded =
        try {
          adapter.fromJson(body)
        } catch (e: JsonEncodingException) {
          // What a non-JSON body — a proxy's error page, say — lands on.
          throw OuraUnavailableException(UNREADABLE)
        } catch (e: JsonDataException) {
          throw OuraUnavailableException(UNREADABLE)
        } catch (e: IOException) {
          throw OuraUnavailableException(UNREADABLE)
        } ?: throw OuraUnavailableException(UNREADABLE)
      decoded.data.orEmpty().filterNotNullTo(items)
      nextToken = decoded.nextToken?.takeIf { it.isNotBlank() }
    } while (nextToken != null)
    return items
  }

  private fun url(
    collection: OuraCollection,
    from: LocalDate,
    to: LocalDate,
    nextToken: String?,
  ): HttpUrl {
    val prefix = if (useSandbox) SANDBOX_PATH else LIVE_PATH
    return "$baseUrl$prefix/${collection.path}"
      .toHttpUrl()
      .newBuilder()
      // Both accept a date or a date-time; a plain ISO date is what the app schedules in.
      .addQueryParameter("start_date", from.toString())
      .addQueryParameter("end_date", to.toString())
      .apply { nextToken?.let { addQueryParameter("next_token", it) } }
      .build()
    // `fields` is deliberately never sent: the sandbox does not accept it, and trimming a response
    // of a few kilobytes buys nothing.
  }

  /**
   * One GET, and the same reading of what went wrong every time.
   *
   * The status is checked before the body is trusted, because a failing service is under no
   * obligation to answer in JSON.
   */
  private suspend fun get(url: HttpUrl, token: String): String =
    withContext(Dispatchers.IO) {
      val request =
        Request.Builder()
          .url(url)
          .header("Authorization", "Bearer $token")
          .header("Accept", "application/json")
          .build()
      val response =
        try {
          calls.newCall(request).execute()
        } catch (e: IOException) {
          throw OuraUnavailableException(OFFLINE)
        }
      response.use {
        when (val code = it.code) {
          200 -> Unit
          400, 422 -> throw OuraRequestException(code)
          401 -> throw OuraAuthException()
          403 -> throw OuraSubscriptionExpiredException()
          429 -> throw OuraRateLimitException()
          else -> throw OuraUnavailableException("Oura vastasi HTTP $code.")
        }
        try {
          it.body?.string() ?: throw OuraUnavailableException(UNREADABLE)
        } catch (e: IOException) {
          throw OuraUnavailableException(OFFLINE)
        }
      }
    }

  companion object {

    /**
     * The real host. **Not** the specification's `servers[0].url`, which is `https://api.None.com`
     * — a placeholder that was never filled in, and what a generator pointed at that file emits.
     */
    const val BASE_URL = "https://api.ouraring.com"

    private const val LIVE_PATH = "/v2/usercollection"

    private const val SANDBOX_PATH = "/v2/sandbox/usercollection"

    /**
     * Well past any real range this app asks for — a year of daily documents is 365 rows — and low
     * enough that a paging bug ends in an error rather than an unbounded loop.
     */
    internal const val MAX_PAGES = 50

    internal const val OFFLINE = "Oura-tietojen haku vaatii verkkoyhteyden."

    internal const val UNREADABLE = "Oura-vastausta ei voitu lukea."

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val dailyScoreAdapter: JsonAdapter<OuraPageDto<OuraDailyScoreDto>> =
      moshi.adapter(
        Types.newParameterizedType(OuraPageDto::class.java, OuraDailyScoreDto::class.java)
      )

    private val workoutAdapter: JsonAdapter<OuraPageDto<OuraWorkoutDto>> =
      moshi.adapter(Types.newParameterizedType(OuraPageDto::class.java, OuraWorkoutDto::class.java))

    /**
     * Timeouts matched to [fi.merilainen.treenivalmentaja.data.guide.GuideHttp]: the app asks for a
     * few kilobytes over a phone connection, and a request that has not answered in ten seconds is
     * better reported than waited on.
     */
    private fun defaultCallFactory(): Call.Factory =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
  }
}
