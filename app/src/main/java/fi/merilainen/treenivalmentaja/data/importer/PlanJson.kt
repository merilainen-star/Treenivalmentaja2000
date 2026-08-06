package fi.merilainen.treenivalmentaja.data.importer

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import fi.merilainen.treenivalmentaja.domain.Exercise
import fi.merilainen.treenivalmentaja.domain.LighterAlternative
import java.io.IOException
import java.security.MessageDigest

/** Parsing and serialisation for the plan import format and the JSON columns in Room. */
object PlanJson {

  // The import DTOs carry use KotlinJsonAdapterFactory.
  // KotlinJsonAdapterFactory is the fallback for the domain classes stored in the JSON columns —
  // it defers to a generated adapter whenever one exists, so the two do not conflict.
  private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

  private val documentAdapter: JsonAdapter<PlanDocumentDto> =
    moshi.adapter(PlanDocumentDto::class.java)

  private val exerciseListAdapter: JsonAdapter<List<Exercise>> =
    moshi.adapter(Types.newParameterizedType(List::class.java, Exercise::class.java))

  private val lighterAdapter: JsonAdapter<LighterAlternative> =
    moshi.adapter(LighterAlternative::class.java)

  /** `null` message means the text could not be read as a plan document at all. */
  fun parse(raw: String): Result<PlanDocumentDto> =
    try {
      val document = documentAdapter.fromJson(raw)
      if (document == null) {
        Result.failure(IllegalArgumentException("tiedosto on tyhjä tai sisältää pelkän null-arvon"))
      } else {
        Result.success(document)
      }
    } catch (e: JsonEncodingException) {
      Result.failure(IllegalArgumentException("teksti ei ole kelvollista JSONia: ${e.message}"))
    } catch (e: JsonDataException) {
      Result.failure(IllegalArgumentException("JSON-rakenne ei vastaa odotettua: ${e.message}"))
    } catch (e: IOException) {
      Result.failure(IllegalArgumentException("tiedostoa ei voitu lukea: ${e.message}"))
    }

  fun encodeExercises(exercises: List<Exercise>?): String? =
    exercises?.takeIf { it.isNotEmpty() }?.let(exerciseListAdapter::toJson)

  fun decodeExercises(json: String?): List<Exercise>? =
    json?.takeIf { it.isNotBlank() }?.let { runCatching { exerciseListAdapter.fromJson(it) }.getOrNull() }

  fun encodeLighter(alternative: LighterAlternative?): String? =
    alternative?.let(lighterAdapter::toJson)

  fun decodeLighter(json: String?): LighterAlternative? =
    json?.takeIf { it.isNotBlank() }?.let { runCatching { lighterAdapter.fromJson(it) }.getOrNull() }

  /**
   * Stable fingerprint of a plan document, used to tell an identical re-import from a conflicting
   * edit. Whitespace is normalised so reformatting the same plan is still recognised as the same
   * plan.
   */
  fun contentHash(raw: String): String {
    val normalised = raw.replace(Regex("\\s+"), " ").trim()
    val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}
