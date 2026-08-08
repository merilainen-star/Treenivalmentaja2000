package fi.merilainen.treenivalmentaja.data.update

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * What GitHub Actions published alongside the APK. See `.github/workflows/build-test-apk.yml`.
 */
@JsonClass(generateAdapter = true)
data class UpdateInfo(
  /** Matches `BuildConfig.VERSION_NAME` of the published build, e.g. `1.0-c07cfac`. */
  val versionName: String,
  val commit: String,
  val builtAtUtc: String,
  val apkUrl: String,
  val apkSizeBytes: Long,
)

/** Fails with an exception rather than returning null, so the caller can say why. */
interface UpdateService {
  suspend fun fetchLatest(): UpdateInfo
}

/**
 * Reads the metadata file from the rolling test release.
 *
 * Deliberately a plain [HttpURLConnection] rather than a new networking dependency: this is one
 * GET of a few hundred bytes, and Retrofit/OkHttp belong to the Oura work that will need them.
 *
 * It also reads the *release asset* rather than GitHub's REST API. Asset downloads are plain file
 * requests with no rate limit, while the unauthenticated API allows only 60 calls an hour per
 * address — a limit an app checking on every visit to Settings could actually hit.
 */
class HttpUpdateService(
  private val url: String = LATEST_JSON_URL,
  private val moshi: Moshi = Moshi.Builder().build(),
) : UpdateService {

  override suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 10_000
      readTimeout = 10_000
      // The asset URL redirects to a CDN host.
      instanceFollowRedirects = true
    }
    try {
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_OK) {
        error("GitHub vastasi HTTP $code")
      }
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      moshi.adapter(UpdateInfo::class.java).fromJson(body)
        ?: error("julkaisun tiedot olivat tyhjät")
    } finally {
      connection.disconnect()
    }
  }

  companion object {
    const val LATEST_JSON_URL =
      "https://github.com/merilainen-star/Treenivalmentaja2000/releases/download/test-build/latest.json"
  }
}
