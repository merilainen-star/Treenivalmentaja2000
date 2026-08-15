package fi.merilainen.treenivalmentaja.data.strava

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the athlete's activities between two dates.
 *
 * One endpoint, unlike Oura's four collections: `GET /api/v3/athlete/activities` with `after` and
 * `before` in epoch seconds. Pagination is by page *number* — there is no `next_token`; the last
 * page is the one that comes back shorter than `per_page`.
 *
 * `internal` because its DTOs are: nothing outside the data layer sees Strava's field names, and
 * what leaves this package are Room rows built by [StravaMappers].
 *
 * @param baseUrl overridden in tests.
 */
internal class StravaClient(
  private val tokens: StravaTokenSource,
  private val baseUrl: String = BASE_URL,
  private val calls: Call.Factory = defaultCallFactory(),
) {

  /**
   * Every activity whose start falls on [from]..[to] in [zone], whatever its sport.
   *
   * The zone matters at the edges: Strava's `after`/`before` are instants, and a run at 23:30
   * local time belongs to that local day, not to the UTC one. Filtering to runs is the caller's
   * decision — the rows are stored as they arrive, sport included, the way Oura's free-form
   * `activity` is.
   */
  suspend fun activities(from: LocalDate, to: LocalDate, zone: ZoneId): List<StravaActivityDto> {
    val token = tokens.accessToken()?.takeIf { it.isNotBlank() } ?: throw StravaNotConnectedException()
    val after = from.atStartOfDay(zone).toEpochSecond()
    val before = to.plusDays(1).atStartOfDay(zone).toEpochSecond()
    val items = mutableListOf<StravaActivityDto>()
    var page = 1
    while (true) {
      if (page > MAX_PAGES) {
        throw StravaUnavailableException(
          "Strava palautti yli $MAX_PAGES sivua tietoja. Hakua ei viety loppuun."
        )
      }
      val url =
        "$baseUrl/api/v3/athlete/activities"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("after", after.toString())
          .addQueryParameter("before", before.toString())
          .addQueryParameter("per_page", PER_PAGE.toString())
          .addQueryParameter("page", page.toString())
          .build()
      val batch = decode(get(url, token))
      batch.filterNotNullTo(items)
      // A page shorter than asked for is the last one. An exactly-full page costs one more request
      // that returns empty — the unavoidable price of paging without a token.
      if (batch.size < PER_PAGE) break
      page++
    }
    return items
  }

  private fun decode(body: String): List<StravaActivityDto?> =
    try {
      adapter.fromJson(body)
    } catch (e: JsonEncodingException) {
      throw StravaUnavailableException(UNREADABLE)
    } catch (e: JsonDataException) {
      throw StravaUnavailableException(UNREADABLE)
    } catch (e: IOException) {
      throw StravaUnavailableException(UNREADABLE)
    } ?: throw StravaUnavailableException(UNREADABLE)

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
          throw StravaUnavailableException(OFFLINE)
        }
      response.use {
        when (val code = it.code) {
          200 -> Unit
          401 -> throw StravaAuthException()
          429 -> throw StravaRateLimitException()
          400, 403, 422 -> throw StravaRequestException(code)
          else -> throw StravaUnavailableException("Strava vastasi HTTP $code.")
        }
        try {
          it.body?.string() ?: throw StravaUnavailableException(UNREADABLE)
        } catch (e: IOException) {
          throw StravaUnavailableException(OFFLINE)
        }
      }
    }

  companion object {

    const val BASE_URL = "https://www.strava.com"

    /** The documented maximum, so a month of training is one request. */
    internal const val PER_PAGE = 100

    /** Far past any real range this app asks for, and low enough to end a paging bug loudly. */
    internal const val MAX_PAGES = 20

    internal const val OFFLINE = "Strava-tietojen haku vaatii verkkoyhteyden."

    internal const val UNREADABLE = "Strava-vastausta ei voitu lukea."

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val adapter: JsonAdapter<List<StravaActivityDto?>> =
      moshi.adapter(Types.newParameterizedType(List::class.java, StravaActivityDto::class.java))

    private fun defaultCallFactory(): Call.Factory =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
  }
}
