package fi.merilainen.treenivalmentaja.data.intervals

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import fi.merilainen.treenivalmentaja.domain.RunTrace

internal object RunTraceJson {
  const val CURRENT_VERSION = 2
  private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RunTrace::class.java)
  fun encode(trace: RunTrace): String = adapter.toJson(trace)
  fun decode(json: String): RunTrace? = try { adapter.fromJson(json) } catch (_: java.io.IOException) { null }
    catch (_: com.squareup.moshi.JsonDataException) { null }

  /** Read the cache marker without allocating all HR/speed samples on each sync. */
  fun isCurrent(json: String): Boolean = try {
    com.squareup.moshi.JsonReader.of(okio.Buffer().writeUtf8(json)).use { reader ->
      reader.beginObject()
      var version = 1
      while (reader.hasNext()) {
        if (reader.nextName() == "formatVersion") version = reader.nextInt() else reader.skipValue()
      }
      reader.endObject()
      version >= CURRENT_VERSION
    }
  } catch (_: java.io.IOException) { false } catch (_: com.squareup.moshi.JsonDataException) { false }
}
