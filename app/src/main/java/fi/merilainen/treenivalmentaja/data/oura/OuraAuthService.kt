package fi.merilainen.treenivalmentaja.data.oura

import com.squareup.moshi.Json
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
 * What the app holds after a successful authorization.
 *
 * @param expiresAtUtc absolute, not a duration, so the answer to "is this still good" does not
 *   depend on when it was asked. `0` means the token endpoint did not say, in which case nothing
 *   refreshes proactively and a `401` is what triggers renewal instead.
 */
internal data class OuraTokens(
  val accessToken: String,
  val refreshToken: String?,
  val expiresAtUtc: Long,
) {

  /**
   * @param skewMillis renew slightly early rather than at the last moment, so a request does not
   *   set off across the network with a token that expires while it is in flight.
   */
  fun isExpired(nowUtc: Long, skewMillis: Long = DEFAULT_SKEW_MILLIS): Boolean =
    expiresAtUtc != UNKNOWN_EXPIRY && nowUtc >= expiresAtUtc - skewMillis

  companion object {
    const val UNKNOWN_EXPIRY = 0L

    private const val DEFAULT_SKEW_MILLIS = 60_000L
  }
}

/**
 * The Oura client credentials, as the build was given them.
 *
 * They arrive in `BuildConfig` from a git-ignored `.env` (ADR-006 in `docs/DECISIONS.md`); a clone
 * with no `.env` builds against the placeholders in `.env.example`, and [isConfigured] is how the
 * rest of the app tells that apart from real credentials without ever comparing secrets itself.
 */
internal data class OuraCredentials(val clientId: String, val clientSecret: String) {

  /** Trimmed, because these arrive pasted and a trailing space is invisible in a text field. */
  fun trimmed(): OuraCredentials =
    OuraCredentials(clientId = clientId.trim(), clientSecret = clientSecret.trim())

  val isConfigured: Boolean
    get() =
      clientId.isNotBlank() &&
        clientSecret.isNotBlank() &&
        !clientId.startsWith(PLACEHOLDER_PREFIX) &&
        !clientSecret.startsWith(PLACEHOLDER_PREFIX)

  private companion object {
    /** What `.env.example` fills in, and therefore what "no credentials" looks like. */
    const val PLACEHOLDER_PREFIX = "placeholder"
  }
}

/**
 * Where the credentials come from, asked each time rather than captured once.
 *
 * They are no longer fixed at build time: the user types them into Settings, so the answer changes
 * while the app is running. A build that *does* carry them in `BuildConfig` is still honoured —
 * see `TreenivalmentajaApplication` — so a local `.env` build keeps working without anyone typing
 * anything.
 */
internal fun interface OuraCredentialsSource {

  suspend fun credentials(): OuraCredentials
}

/**
 * The token endpoint: authorization code in, tokens out, and the same for a refresh.
 *
 * Separate from [OuraClient] because it is a different endpoint with a different content type — a
 * form post, not a JSON GET — and because it must never be routed through the `Authenticator` that
 * calls it, which would be a loop.
 *
 * @param tokenUrl overridden in tests.
 */
internal class OuraAuthService(
  private val credentials: OuraCredentialsSource,
  private val tokenUrl: String = OuraOAuth.TOKEN_URL,
  private val calls: Call.Factory = defaultCallFactory(),
  private val now: () -> Long = System::currentTimeMillis,
) {

  /**
   * Trades an authorization code for tokens.
   *
   * [codeVerifier] is the PKCE half that never went through the browser. Sending it here is what
   * proves this exchange belongs to the request that started the flow.
   */
  suspend fun exchange(code: String, codeVerifier: String): OuraTokens =
    post(
      FormBody.Builder()
        .add("grant_type", "authorization_code")
        .add("code", code)
        .add("redirect_uri", OuraOAuth.REDIRECT_URI)
        .add("code_verifier", codeVerifier)
        .build()
    )

  /**
   * Spends a refresh token for a new pair.
   *
   * Oura rotates: the response carries a *new* refresh token and the old one is done. Persisting
   * what comes back is not optional, and spending the same refresh token twice is the failure this
   * has to be protected from — see [OuraAuthenticator], which serialises callers for that reason.
   */
  suspend fun refresh(refreshToken: String): OuraTokens =
    post(
      FormBody.Builder()
        .add("grant_type", "refresh_token")
        .add("refresh_token", refreshToken)
        .build()
    )

  private suspend fun post(form: FormBody): OuraTokens {
    val credentials = credentials.credentials()
    if (!credentials.isConfigured) throw OuraNotConfiguredException()
    val body =
      FormBody.Builder()
        .apply {
          for (i in 0 until form.size) add(form.name(i), form.value(i))
          // Oura's token endpoint requires client authentication; a secret-less public client is
          // not supported, which is half the reason ADR-006 exists.
          add("client_id", credentials.clientId)
          add("client_secret", credentials.clientSecret)
        }
        .build()
    val request = Request.Builder().url(tokenUrl).post(body).header("Accept", "application/json").build()
    val (code, text) =
      withContext(Dispatchers.IO) {
        val response =
          try {
            calls.newCall(request).execute()
          } catch (e: IOException) {
            throw OuraUnavailableException(OuraClient.OFFLINE)
          }
        response.use { it.code to (it.body?.string().orEmpty()) }
      }
    if (code != 200) throw OuraAuthorizationException(describe(code, text))
    val parsed = readToken(text)
    val accessToken =
      parsed.accessToken?.takeIf { it.isNotBlank() }
        ?: throw OuraAuthorizationException("Oura ei palauttanut käyttöoikeustunnusta.")
    return OuraTokens(
      accessToken = accessToken,
      refreshToken = parsed.refreshToken?.takeIf { it.isNotBlank() },
      expiresAtUtc =
        parsed.expiresIn?.takeIf { it > 0 }?.let { now() + it * 1000 } ?: OuraTokens.UNKNOWN_EXPIRY,
    )
  }

  /**
   * A rejection in the user's language, without putting the endpoint's own wording on screen.
   *
   * OAuth2 error codes are a small closed set and they mean different things to a person: an
   * expired code is worth retrying by connecting again, wrong client credentials are the build's
   * problem and no amount of tapping will fix them.
   */
  private fun describe(status: Int, body: String): String {
    val error = runCatching { errorAdapter.fromJson(body)?.error }.getOrNull()
    return when (error) {
      "invalid_grant" -> "Oura hylkäsi tunnistautumisen. Yhdistä Oura uudelleen."
      "invalid_client" -> "Oura-tunnukset eivät kelpaa. Tarkista .env-tiedoston arvot."
      "invalid_scope" -> "Oura ei myöntänyt pyydettyjä oikeuksia."
      else -> "Oura-tunnistautuminen epäonnistui (HTTP $status)."
    }
  }

  private fun readToken(body: String): TokenResponseDto =
    try {
      tokenAdapter.fromJson(body)
    } catch (e: JsonEncodingException) {
      throw OuraAuthorizationException(OuraClient.UNREADABLE)
    } catch (e: JsonDataException) {
      throw OuraAuthorizationException(OuraClient.UNREADABLE)
    } catch (e: IOException) {
      throw OuraAuthorizationException(OuraClient.UNREADABLE)
    } ?: throw OuraAuthorizationException(OuraClient.UNREADABLE)

  private companion object {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    val tokenAdapter = moshi.adapter(TokenResponseDto::class.java)

    val errorAdapter = moshi.adapter(TokenErrorDto::class.java)

    fun defaultCallFactory(): Call.Factory =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
  }
}

/** The token endpoint's success body. Every field nullable, for the reason [OuraDailyScoreDto] is. */
private data class TokenResponseDto(
  @Json(name = "access_token") val accessToken: String? = null,
  @Json(name = "refresh_token") val refreshToken: String? = null,
  /** Seconds. */
  @Json(name = "expires_in") val expiresIn: Long? = null,
  @Json(name = "token_type") val tokenType: String? = null,
)

/** Its failure body, in the OAuth2 shape. */
private data class TokenErrorDto(
  val error: String? = null,
  @Json(name = "error_description") val errorDescription: String? = null,
)
