package fi.merilainen.treenivalmentaja.data.guide

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One GET, and the same reading of what went wrong for every guide provider.
 *
 * A plain [HttpURLConnection] for the same reason [fi.merilainen.treenivalmentaja.data.update
 * .HttpUpdateService] is one: these are single requests of a few kilobytes, and the app has no
 * networking stack to reuse yet.
 *
 * Shared because the failures are the provider's, not the endpoint's: a movement the source does
 * not have, a rate limit, a service that is down, a phone with no network. Two copies of this
 * would drift, and the sheet would start reading differently depending on which source a plan
 * happened to name.
 */
internal object GuideHttp {

  const val OFFLINE = "Liiketiedot vaativat verkkoyhteyden."

  const val UNREADABLE = "Liiketietoja ei voitu lukea."

  /**
   * @param onErrorCode inspected before the generic mapping, so one endpoint can give a status its
   *   own meaning — a 404 from a lookup by id is "no such movement", not "the service is down".
   */
  suspend fun get(
    url: String,
    onErrorCode: (Int) -> Unit = {},
  ): String = withContext(Dispatchers.IO) {
    val connection =
      try {
        (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 10_000
          readTimeout = 10_000
          setRequestProperty("Accept", "application/json")
        }
      } catch (e: IOException) {
        throw GuideUnavailableException(OFFLINE)
      }
    try {
      val code =
        try {
          connection.responseCode
        } catch (e: IOException) {
          throw GuideUnavailableException(OFFLINE)
        }
      if (code != HttpURLConnection.HTTP_OK) {
        onErrorCode(code)
        throw GuideUnavailableException(
          when (code) {
            429 -> "Liiketietoja haettiin liian tiheästi. Yritä hetken päästä."
            else -> "Liiketietolähde vastasi HTTP $code."
          }
        )
      }
      try {
        connection.inputStream.bufferedReader().use { it.readText() }
      } catch (e: IOException) {
        throw GuideUnavailableException(OFFLINE)
      }
    } finally {
      connection.disconnect()
    }
  }
}
