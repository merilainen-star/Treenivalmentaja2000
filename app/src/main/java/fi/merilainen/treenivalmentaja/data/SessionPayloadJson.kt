package fi.merilainen.treenivalmentaja.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import fi.merilainen.treenivalmentaja.domain.GuidedProgress

/**
 * What goes in `session_events.payloadJson`, and how it is read back.
 *
 * That column is one shared slot on an append-only table, and it already carries a reschedule's
 * `{"fromDate":…,"toDate":…}`. So every payload is written **under a key that names it** rather
 * than as a bare object: a reader asking for guided progress on a reschedule row gets `null`
 * because the `guided` key is absent, not because parsing happened to fail. Two payload shapes
 * that both parse as each other is precisely the bug this shape rules out.
 */
internal object SessionPayloadJson {

  private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

  private val adapter: JsonAdapter<Payload> = moshi.adapter(Payload::class.java)

  /**
   * The envelope. Every field is nullable so an unrelated payload decodes to a `Payload` with
   * nothing in it, which is the honest reading of "this row is about something else".
   */
  private data class Payload(val guided: GuidedProgress? = null)

  fun encodeGuidedProgress(progress: GuidedProgress): String = adapter.toJson(Payload(progress))

  /**
   * `null` for a row that carries no guided progress — a reschedule, an older completion written
   * before this existed, or a payload that cannot be read at all. A session completed before this
   * feature shipped is genuinely missing the information, and must not decode to zero movements
   * done: that would tell the coach the workout was abandoned.
   */
  fun decodeGuidedProgress(json: String?): GuidedProgress? =
    json?.takeIf { it.isNotBlank() }?.let { runCatching { adapter.fromJson(it) }.getOrNull() }?.guided
}
