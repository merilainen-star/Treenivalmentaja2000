package fi.merilainen.treenivalmentaja.data.strava

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What the app holds after a successful Strava authorization.
 *
 * @param expiresAtUtc absolute epoch millis. Strava access tokens live six hours and the endpoint
 *   states their expiry absolutely (`expires_at`, epoch seconds), so unlike Oura there is no
 *   arithmetic against a local clock at parse time beyond the unit change. `0` means the endpoint
 *   did not say.
 */
internal data class StravaTokens(
  val accessToken: String,
  val refreshToken: String?,
  val expiresAtUtc: Long,
) {

  companion object {
    const val UNKNOWN_EXPIRY = 0L
  }
}

/** The Strava client credentials the user typed into Settings. */
internal data class StravaCredentials(val clientId: String, val clientSecret: String) {

  /** Trimmed, because these arrive pasted and a trailing space is invisible in a text field. */
  fun trimmed(): StravaCredentials =
    StravaCredentials(clientId = clientId.trim(), clientSecret = clientSecret.trim())

  val isConfigured: Boolean
    get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/**
 * Where the credentials come from, asked each time rather than captured once — the user types them
 * into Settings, so the answer changes while the app is running.
 *
 * No `BuildConfig` fallback, unlike Oura: that path exists there for local `.env` builds that
 * predate typed credentials, and Strava has no such history to honour.
 */
internal fun interface StravaCredentialsSource {

  suspend fun credentials(): StravaCredentials?
}

/**
 * The token endpoint: authorization code in, tokens out, and the same for a refresh.
 *
 * Separate from [StravaClient] for the reason the Oura pair is separate: a different endpoint with
 * a different content type, and it must never be routed through the `Authenticator` that calls it.
 *
 * @param tokenUrl overridden in tests.
 */
internal class StravaAuthService(
  private val credentials: StravaCredentialsSource,
  private val tokenUrl: String = StravaOAuth.TOKEN_URL,
  private val calls: Call.Factory = defaultCallFactory(),
) {

  /** Trades an authorization code for tokens. No PKCE — the client secret is the proof. */
  suspend fun exchange(code: String): StravaTokens =
    post(
      FormBody.Builder()
        .add("grant_type", "authorization_code")
        .add("code", code)
        .build()
    )

  /**
   * Spends a refresh token for a new pair.
   *
   * Strava answers with a refresh token on every refresh — usually the same one, but the
   * documentation reserves the right to rotate, so what comes back is always what gets stored.
   */
  suspend fun refresh(refreshToken: String): StravaTokens =
    post(
      FormBody.Builder()
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .build()
    )

  private suspend fun post(form: FormBody): StravaTokens {
    val credentials =
      credentials.credentials()?.takeIf { it.isConfigured } ?: throw StravaNotConfiguredException()
    val body =
      FormBody.Builder()
        .apply {
          for (i in 0 until form.size) add(form.name(i), form.value(i))
          add("client_id", credentials.clientId)
          add("client_secret", credentials.clientSecret)
        }
        .build()
    val request =
      Request.Builder().url(tokenUrl).post(body).header("Accept", "application/json").build()
    val (code, text) =
      withContext(Dispatchers.IO) {
        val response =
          try {
            calls.newCall(request).execute()
          } catch (e: IOException) {
            throw StravaUnavailableException(StravaClient.OFFLINE)
          }
        response.use { it.code to (it.body?.string().orEmpty()) }
      }
    if (code != 200) throw StravaAuthorizationException(describe(code, text))
    val parsed = readToken(text)
    val accessToken =
      parsed.accessToken?.takeIf { it.isNotBlank() }
        ?: throw StravaAuthorizationException("Strava ei palauttanut käyttöoikeustunnusta.")
    return StravaTokens(
      accessToken = accessToken,
      refreshToken = parsed.refreshToken?.takeIf { it.isNotBlank() },
      expiresAtUtc =
        parsed.expiresAt?.takeIf { it > 0 }?.times(1000) ?: StravaTokens.UNKNOWN_EXPIRY,
    )
  }

  /**
   * A rejection in the user's language. Strava's token endpoint answers errors with a `message`
   * ("Bad Request", "Authorization Error") rather than OAuth2 error codes, so the status is what
   * there is to go on.
   */
  private fun describe(status: Int, body: String): String {
    val message = runCatching { errorAdapter.fromJson(body)?.message }.getOrNull()
    return when {
      status == 400 || status == 401 ->
        "Strava hylkäsi tunnistautumisen. Tarkista tunnukset ja yhdistä uudelleen."
      message != null -> "Strava-tunnistautuminen epäonnistui: $message"
      else -> "Strava-tunnistautuminen epäonnistui (HTTP $status)."
    }
  }

  private fun readToken(body: String): StravaTokenResponseDto =
    try {
      tokenAdapter.fromJson(body)
    } catch (e: JsonEncodingException) {
      throw StravaAuthorizationException(StravaClient.UNREADABLE)
    } catch (e: JsonDataException) {
      throw StravaAuthorizationException(StravaClient.UNREADABLE)
    } catch (e: IOException) {
      throw StravaAuthorizationException(StravaClient.UNREADABLE)
    } ?: throw StravaAuthorizationException(StravaClient.UNREADABLE)

  private companion object {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    val tokenAdapter = moshi.adapter(StravaTokenResponseDto::class.java)

    val errorAdapter = moshi.adapter(StravaTokenErrorDto::class.java)

    fun defaultCallFactory(): Call.Factory =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
  }
}
