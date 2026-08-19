package fi.merilainen.treenivalmentaja.data.intervals

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import fi.merilainen.treenivalmentaja.domain.IntervalsRawResponse
import java.io.IOException
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the athlete's activities from intervals.icu.
 *
 * One endpoint and no pagination: `GET /api/v1/athlete/{id}/activities` returns the whole date
 * range in one array, in descending date order. `oldest` is required by the specification and
 * `newest` defaults to now.
 *
 * **Authentication is HTTP Basic with the literal username `API_KEY`** and the personal key as the
 * password — the specification says so in as many words ("Username is API_KEY, Password is your
 * API key found in /settings"). There is no OAuth here on purpose: this is a single-user app, and
 * an authorization-code flow with a browser round trip, a callback activity and refresh-token
 * rotation would be three moving parts serving one person's own key.
 *
 * `internal` because its DTOs are: nothing outside the data layer sees intervals.icu's field
 * names, and what leaves this package are Room rows built by [IntervalsMappers].
 *
 * @param baseUrl overridden in tests.
 */
internal class IntervalsClient(
  private val apiKeys: IntervalsApiKeySource,
  private val baseUrl: String = BASE_URL,
  private val calls: Call.Factory = defaultCallFactory(),
) {

  /**
   * Every activity between two dates, whatever its sport.
   *
   * Filtering to runs is the caller's decision — rows are stored as they arrive, sport included,
   * the way Oura's free-form `activity` is. The dates are local ISO-8601, which is what the
   * parameters are documented to take.
   */
  suspend fun activities(from: LocalDate, to: LocalDate): List<IntervalsActivityDto> {
    val url =
      activitiesUrl()
        .newBuilder()
        .addQueryParameter("oldest", from.toString())
        .addQueryParameter("newest", to.toString())
        .addQueryParameter("fields", FIELDS)
        .build()
    return decode(get(url))
  }

  /**
   * Proves a key works, by making the same request the sync makes and asking for one activity.
   *
   * Deliberately not `/athlete/{id}/profile`: a key that works for the profile endpoint has not
   * been shown to work for the one the app actually depends on. Returning the count rather than
   * the rows keeps this from looking like a way to fetch data.
   *
   * @return how many activities came back — 0 is a success, not a failure. A new athlete has none.
   */
  suspend fun testKey(): Int {
    val url =
      activitiesUrl()
        .newBuilder()
        // A year back rather than a week: an athlete who has not trained recently still gets a
        // reassuring answer, and `limit` keeps it to one row either way.
        .addQueryParameter("oldest", LocalDate.now().minusYears(1).toString())
        .addQueryParameter("limit", "1")
        .addQueryParameter("fields", "id")
        .build()
    return decode(get(url)).size
  }

  /**
   * One day's training load per day, decayed — `GET /api/v1/athlete/{id}/wellness`.
   *
   * **Not the same numbers as the activities' own `icu_atl`/`icu_ctl`.** Those are frozen at the
   * moment of a session and never decay; these are the daily series, which is what "how loaded is
   * the athlete today" means. See [IntervalsWellnessDto].
   *
   * Only `ctl`, `atl` and `rampRate` are read, of the 46 the `Wellness` schema declares. The record
   * also carries `hrv`, `restingHR` and `sleepScore`, and those are deliberately left alone: Oura is
   * already this app's source for them.
   *
   * The specification templates this path as `/wellness{ext}`, with the extension a required path
   * parameter. The plain form is what is used here; if the real service ever answers `404`, `.json`
   * is the other candidate. The caller treats a failure as "no load figures" rather than as a failed
   * sync, so a wrong guess costs the analysis its load section and nothing else.
   */
  suspend fun wellness(from: LocalDate, to: LocalDate): List<IntervalsWellnessDto> {
    val url =
      "$baseUrl/api/v1/athlete/$SELF/wellness"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("oldest", from.toString())
        .addQueryParameter("newest", to.toString())
        .build()
    return decodeList(get(url), wellnessAdapter)
  }

  private fun activitiesUrl(): HttpUrl = "$baseUrl/api/v1/athlete/$SELF/activities".toHttpUrl()

  // ------------------------------------------------------------------ diagnostics

  /**
   * The activities response **exactly as the server sends it**, for the diagnostics screen.
   *
   * Two things make this different from [activities], and both are the point:
   *
   * 1. **No `fields` parameter.** The ordinary request names eighteen fields and gets eighteen
   *    back; this one names none, so all 183 arrive. The whole reason to look at raw data is to
   *    find something the app is not already asking for — a duration field that matches the
   *    watch, say — and filtering the response would hide precisely that.
   * 2. **No parsing.** The body is returned as text. Nothing is decoded into a DTO, so nothing can
   *    be dropped, renamed or reformatted on the way to the screen.
   *
   * A non-200 is returned rather than thrown, because on this screen the status *is* the finding.
   */
  suspend fun rawActivities(from: LocalDate, to: LocalDate): IntervalsRawResponse =
    fetchRaw(
      activitiesUrl()
        .newBuilder()
        .addQueryParameter("oldest", from.toString())
        .addQueryParameter("newest", to.toString())
        .build()
    )

  /**
   * One activity in full, from the documented `GET /api/v1/activity/{id}`.
   *
   * `intervals=true` is passed because the parameter exists for it and the lap and interval
   * breakdown is exactly the kind of thing a summary omits — this endpoint is here to show more
   * than the list does, so asking for less would waste the trip.
   */
  suspend fun rawActivity(activityId: String): IntervalsRawResponse =
    fetchRaw(
      "$baseUrl/api/v1/activity/$activityId"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("intervals", "true")
        .build()
    )

  /**
   * One GET whose status and body are both reported, whatever they are.
   *
   * **The returned [IntervalsRawResponse.endpoint] is built from the URL alone.** The
   * `Authorization` header is attached to the request and never recorded anywhere that reaches a
   * screen, a clipboard or a log — the API key must not be able to leak through a diagnostics
   * feature, which is the one place someone is likely to copy the contents and paste them
   * somewhere else.
   */
  private suspend fun fetchRaw(url: HttpUrl): IntervalsRawResponse {
    val key = apiKeys.apiKey()?.takeIf { it.isNotBlank() } ?: throw IntervalsNotConfiguredException()
    // Path and query only: `HttpUrl.encodedPath` and `query` cannot contain a credential, because
    // the credential travels in a header.
    val line = "GET ${url.encodedPath}" + (url.query?.let { "?$it" } ?: "")
    return withContext(Dispatchers.IO) {
      val request =
        Request.Builder()
          .url(url)
          .header("Authorization", basic(key))
          .header("Accept", "application/json")
          .build()
      val response =
        try {
          calls.newCall(request).execute()
        } catch (e: IOException) {
          throw IntervalsUnavailableException(OFFLINE)
        }
      response.use {
        val body =
          try {
            it.body?.string().orEmpty()
          } catch (e: IOException) {
            throw IntervalsUnavailableException(OFFLINE)
          }
        IntervalsRawResponse(
          endpoint = line,
          status = it.code,
          body = body,
          fetchedAtUtc = System.currentTimeMillis(),
        )
      }
    }
  }

  /** The same reading of an unreadable body, for any list-shaped response. */
  private fun <T : Any> decodeList(body: String, listAdapter: JsonAdapter<List<T?>>): List<T> =
    try {
      listAdapter.fromJson(body)?.filterNotNull()
    } catch (e: JsonEncodingException) {
      throw IntervalsUnavailableException(UNREADABLE)
    } catch (e: JsonDataException) {
      throw IntervalsUnavailableException(UNREADABLE)
    } catch (e: IOException) {
      throw IntervalsUnavailableException(UNREADABLE)
    } ?: throw IntervalsUnavailableException(UNREADABLE)

  private fun decode(body: String): List<IntervalsActivityDto> =
    try {
      adapter.fromJson(body)?.filterNotNull()
    } catch (e: JsonEncodingException) {
      // What a non-JSON body — a proxy's error page, say — lands on.
      throw IntervalsUnavailableException(UNREADABLE)
    } catch (e: JsonDataException) {
      throw IntervalsUnavailableException(UNREADABLE)
    } catch (e: IOException) {
      throw IntervalsUnavailableException(UNREADABLE)
    } ?: throw IntervalsUnavailableException(UNREADABLE)

  /**
   * One GET, and the same reading of what went wrong every time.
   *
   * The status is checked before the body is trusted, because a failing service is under no
   * obligation to answer in JSON.
   */
  private suspend fun get(url: HttpUrl): String {
    val key = apiKeys.apiKey()?.takeIf { it.isNotBlank() } ?: throw IntervalsNotConfiguredException()
    return withContext(Dispatchers.IO) {
      val request =
        Request.Builder()
          .url(url)
          .header("Authorization", basic(key))
          .header("Accept", "application/json")
          .build()
      val response =
        try {
          calls.newCall(request).execute()
        } catch (e: IOException) {
          throw IntervalsUnavailableException(OFFLINE)
        }
      response.use {
        when (val code = it.code) {
          200 -> Unit
          401 -> throw IntervalsAuthException()
          403 -> throw IntervalsForbiddenException()
          // The service's own number when it sends one, rather than a guess of ours dressed up as
          // its instruction.
          429 ->
            throw IntervalsRateLimitException(
              it.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { seconds -> seconds > 0 }
            )
          400, 422 -> throw IntervalsRequestException(code)
          else -> throw IntervalsUnavailableException("Intervals.icu vastasi HTTP $code.")
        }
        try {
          it.body?.string() ?: throw IntervalsUnavailableException(UNREADABLE)
        } catch (e: IOException) {
          throw IntervalsUnavailableException(OFFLINE)
        }
      }
    }
  }

  /**
   * `Basic base64(API_KEY:<key>)`.
   *
   * Built here rather than with OkHttp's `Credentials.basic`, which encodes with ISO-8859-1 by
   * default; the key is ASCII either way, but the encoding of a credential should not be an
   * assumption about the key's alphabet.
   */
  private fun basic(key: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$USERNAME:$key".toByteArray(Charsets.UTF_8))

  companion object {

    const val BASE_URL = "https://intervals.icu"

    /** The literal username the specification requires. Not a placeholder for the athlete's name. */
    internal const val USERNAME = "API_KEY"

    /**
     * `0` means "the athlete this key belongs to", which is documented and saves storing an id the
     * user would otherwise have to look up and paste alongside the key.
     */
    internal const val SELF = "0"

    /**
     * The twenty-five fields the app reads, of the **183** the `Activity` schema declares.
     *
     * Naming them is not a micro-optimisation: without this the service sends every property of
     * every activity in the range, which for a fortnight of training is a large multiple of what
     * is used. The parameter also drops nulls from the response, per the specification.
     *
     * Three groups, and the split is deliberate. The **watch's own numbers** — `distance`,
     * `average_speed`, `max_speed`, heart rate, cadence, elevation, calories — are what a runner
     * recognises, and `average_speed` is the one that reproduces the Suunto's own duration.
     * **intervals.icu's analysis** — `moving_time`, `icu_recording_time`, `icu_training_load`,
     * `icu_intensity`, `hr_load`, `trimp` — is richer than anything the watch shows and is kept
     * beside it rather than instead of it. `distance` and `icu_distance` are both here because
     * the specification describes neither and does not say how they differ.
     */
    internal const val FIELDS =
      "id,name,type,start_date,start_date_local," +
        "moving_time,elapsed_time,icu_recording_time," +
        "distance,icu_distance,average_speed,max_speed," +
        "average_heartrate,max_heartrate,average_cadence," +
        "total_elevation_gain,calories," +
        "icu_training_load,icu_intensity,hr_load,trimp,icu_atl,icu_ctl," +
        "source,device_name"

    internal const val OFFLINE = "Intervals.icu-tietojen haku vaatii verkkoyhteyden."

    internal const val UNREADABLE = "Intervals.icu-vastausta ei voitu lukea."

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val adapter: JsonAdapter<List<IntervalsActivityDto?>> =
      moshi.adapter(Types.newParameterizedType(List::class.java, IntervalsActivityDto::class.java))

    private val wellnessAdapter: JsonAdapter<List<IntervalsWellnessDto?>> =
      moshi.adapter(Types.newParameterizedType(List::class.java, IntervalsWellnessDto::class.java))

    /** Matched to the other clients in this app: a few kilobytes over a phone connection. */
    private fun defaultCallFactory(): Call.Factory =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
  }
}
