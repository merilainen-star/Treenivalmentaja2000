package fi.merilainen.treenivalmentaja.data.guide

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.Locale

/**
 * The free ExerciseDB V1 API, <https://oss.exercisedb.dev/docs>.
 *
 * The source with an animation for every movement, and the reason the guide sheet is worth
 * opening at all. What it does not have is several bodyweight basics — see [WgerProvider], which
 * covers those.
 *
 * Nothing here is stored. The terms of use forbid keeping what the API returns, so the only cache
 * is the in-memory one in `LoadExerciseGuideUseCase`, which dies with the process. Attribution is
 * required and is shown wherever this data appears.
 *
 * Measured against the live service on 2026-08-09; every quirk below was observed, not assumed:
 *
 * - The service sits behind Cloudflare Workers and answers `503` with the **plain text** body
 *   `error code: 1102` when it is over its resource limit. A body is therefore never assumed to
 *   be JSON, and the status is checked first.
 * - `?name=` matches fuzzily and confidently returns nonsense: `name=cat cow` came back with
 *   "cable squat row". Everything it returns is filtered through [relevantTo] before it is
 *   allowed anywhere near the screen. The `/exercises/search` endpoint is worse — at its default
 *   threshold `search=kissa` returned seven unrelated results — so it is not used at all.
 */
class ExerciseDbProvider(
  private val baseUrl: String = BASE_URL,
) : ExerciseGuideProvider {

  override val id: String = GuideProviders.EXERCISEDB

  override val attribution: String = ATTRIBUTION

  override suspend fun byId(id: String): ExerciseGuide {
    val body = GuideHttp.get("$baseUrl/exercises/${encodePathSegment(id)}") { code ->
      if (code == HttpURLConnection.HTTP_NOT_FOUND) {
        throw GuideNotFoundException("Liikettä ei löytynyt lähteestä.")
      }
    }
    return parseOne(body)
  }

  override suspend fun search(name: String): List<ExerciseGuide> {
    val query = URLEncoder.encode(name, "UTF-8")
    val body = GuideHttp.get("$baseUrl/exercises?name=$query&limit=$SEARCH_LIMIT")
    return relevantTo(name, parseMany(body))
  }

  companion object {
    const val BASE_URL = "https://oss.exercisedb.dev/api/v1"

    /** The API's own maximum. Trimmed to [MAX_SUGGESTIONS] once the noise is filtered out. */
    private const val SEARCH_LIMIT = 25

    private const val MAX_SUGGESTIONS = 5

    /** A word shorter than this matches too much to mean anything. */
    private const val MIN_TOKEN_LENGTH = 3

    /** Required by the free tier: "Credit to AscendAPI is required … in any project". */
    internal const val ATTRIBUTION = "Liiketiedot: ExerciseDB / AscendAPI"

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val oneAdapter = moshi.adapter(SingleResponseDto::class.java)

    private val manyAdapter = moshi.adapter(ListResponseDto::class.java)

    private fun encodePathSegment(raw: String): String =
      URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /**
     * Separate from the request so both can be tested against payloads captured from the live
     * service. A Moshi mismatch in this project has shipped before, compiling cleanly and failing
     * on the phone; a hand-written body would not have caught it.
     */
    internal fun parseOne(json: String): ExerciseGuide {
      val response = readJson { oneAdapter.fromJson(json) }
      val exercise = response?.data ?: throw GuideUnavailableException(GuideHttp.UNREADABLE)
      return exercise.toGuide() ?: throw GuideUnavailableException(GuideHttp.UNREADABLE)
    }

    internal fun parseMany(json: String): List<ExerciseGuide> {
      val response = readJson { manyAdapter.fromJson(json) }
      return response?.data.orEmpty().mapNotNull { it?.toGuide() }
    }

    private fun <T> readJson(block: () -> T): T =
      try {
        block()
      } catch (e: JsonEncodingException) {
        // What a Cloudflare "error code: 1102" body lands on.
        throw GuideUnavailableException(GuideHttp.UNREADABLE)
      } catch (e: JsonDataException) {
        throw GuideUnavailableException(GuideHttp.UNREADABLE)
      } catch (e: IOException) {
        throw GuideUnavailableException(GuideHttp.UNREADABLE)
      }

    /**
     * Drops everything whose name does not actually contain every word of the query.
     *
     * The service's fuzzy matching does not miss — it invents. `name=cat cow` answers with
     * "cable squat row", and a Finnish name answers with whatever is alphabetically unlucky.
     * Showing "Tarkoititko: cable squat row?" for "Kissa-lehmä" is worse than showing nothing, so
     * a match has to earn its place by sharing whole words.
     *
     * Ranked by name length, shortest first: among names containing every query word, the short
     * one is the plain movement and the long ones are its variants.
     */
    internal fun relevantTo(query: String, candidates: List<ExerciseGuide>): List<ExerciseGuide> {
      val tokens =
        query
          .lowercase(Locale.ROOT)
          .split(Regex("[^\\p{L}\\p{Nd}]+"))
          .filter { it.length >= MIN_TOKEN_LENGTH }
      if (tokens.isEmpty()) return emptyList()
      return candidates
        .filter { candidate ->
          val name = candidate.name.lowercase(Locale.ROOT)
          tokens.all { name.contains(it) }
        }
        .sortedBy { it.name.length }
        .take(MAX_SUGGESTIONS)
    }
  }
}

/**
 * The API's envelope. Every field is nullable because the free service is not versioned in any way
 * this app controls, and a missing field should degrade the sheet rather than crash it.
 */
private data class SingleResponseDto(val data: ExerciseDbDto? = null)

private data class ListResponseDto(val data: List<ExerciseDbDto?>? = null)

/**
 * One exercise as the service returns it: eight fields, none of which is reps, sets or duration.
 * That absence is the point — see [ExerciseGuide].
 */
private data class ExerciseDbDto(
  val exerciseId: String? = null,
  val name: String? = null,
  val gifUrl: String? = null,
  val instructions: List<String?>? = null,
  val targetMuscles: List<String?>? = null,
  val secondaryMuscles: List<String?>? = null,
  val bodyParts: List<String?>? = null,
  val equipments: List<String?>? = null,
)

/** `null` when the row is too incomplete to show: an unnamed movement is not worth a sheet. */
private fun ExerciseDbDto.toGuide(): ExerciseGuide? {
  val id = exerciseId?.takeIf { it.isNotBlank() } ?: return null
  val name = name?.takeIf { it.isNotBlank() } ?: return null
  return ExerciseGuide(
    id = id,
    name = name,
    imageUrl = gifUrl.orEmpty(),
    // "Step:1 Lie flat on a bench…" — the source numbers its own steps, and the sheet numbers
    // them again, so the prefix is dropped rather than printed twice.
    instructions = instructions.clean().map { it.replace(STEP_PREFIX, "") },
    targetMuscles = targetMuscles.clean(),
    equipment = equipments.clean(),
    // One line for the whole source: ExerciseDB credits itself, not per-movement authors.
    attribution = ExerciseDbProvider.ATTRIBUTION,
  )
}

private val STEP_PREFIX = Regex("^Step:\\d+\\s*")

private fun List<String?>?.clean(): List<String> =
  orEmpty().filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
