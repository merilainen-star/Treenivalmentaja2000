package fi.merilainen.treenivalmentaja.data.guide

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * wger, <https://wger.de>, the second guide source — and the one that has the movements
 * ExerciseDB simply does not.
 *
 * Measured against the live API on 2026-08-09. What it is good for, and what it is not:
 *
 * - **It has the basics.** Plank, side plank, plain squat, bird dog and cat-cow are all absent
 *   from ExerciseDB's 1500 and all present here. Bulgarian split squats even exist per side,
 *   which is what a `perSide` movement actually asks for.
 * - **Its pictures are stills, and there are not many.** 360 images across 834 movements — a
 *   third of them — and almost all are PNG or JPEG rather than anything animated. A movement with
 *   no picture still gets its name and instructions, which is why [ExerciseGuide.imageUrl] is
 *   allowed to be blank.
 * - **Its licences permit what ExerciseDB's forbid.** Content is CC-BY-SA, CC-BY, CC0 or ODbL,
 *   and every image names its own author — so the credit line is built per guide rather than per
 *   source. The app still stores nothing, because the other source's terms rule that out and one
 *   image loader serves both.
 *
 * [search] deliberately makes no request at all; see its own comment.
 */
class WgerProvider(
  private val baseUrl: String = BASE_URL,
) : ExerciseGuideProvider {

  override val id: String = GuideProviders.WGER

  override val attribution: String = ATTRIBUTION

  override suspend fun byId(id: String): ExerciseGuide {
    val body = GuideHttp.get("$baseUrl/exerciseinfo/${encodePathSegment(id)}/?format=json") { code ->
      if (code == HttpURLConnection.HTTP_NOT_FOUND) {
        throw GuideNotFoundException("Liikettä ei löytynyt lähteestä.")
      }
    }
    return parseExerciseInfo(body)
  }

  /**
   * Always empty, and without touching the network.
   *
   * wger removed `/exercise/search/` — it answers `404` — and the filter that remains,
   * `?name=`, is an exact **case-sensitive** match: `name=Bird Dog` returns four rows and
   * `name=bird dog` returns none. A Finnish movement name cannot hit that under any
   * capitalisation, so a request here would be a guaranteed miss made against a volunteer-run
   * service on every tap.
   *
   * The fuzzy path stays with [ExerciseDbProvider], which has a working one. Reaching a wger
   * movement is what `guide` is for.
   */
  override suspend fun search(name: String): List<ExerciseGuide> = emptyList()

  companion object {
    const val BASE_URL = "https://wger.de/api/v2"

    /** The English translation. wger has no Finnish; the reference is what settles the movement. */
    private const val ENGLISH = 2

    internal const val ATTRIBUTION = "Liiketiedot: wger.de"

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val infoAdapter = moshi.adapter(ExerciseInfoDto::class.java)

    private fun encodePathSegment(raw: String): String =
      URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    /**
     * Separate from the request so it can be tested against payloads captured from the live API,
     * including one movement that has no picture at all.
     */
    internal fun parseExerciseInfo(json: String): ExerciseGuide {
      val info =
        try {
          infoAdapter.fromJson(json)
        } catch (e: JsonEncodingException) {
          throw GuideUnavailableException(GuideHttp.UNREADABLE)
        } catch (e: JsonDataException) {
          throw GuideUnavailableException(GuideHttp.UNREADABLE)
        } catch (e: IOException) {
          throw GuideUnavailableException(GuideHttp.UNREADABLE)
        } ?: throw GuideUnavailableException(GuideHttp.UNREADABLE)

      val id = info.id?.toString() ?: throw GuideUnavailableException(GuideHttp.UNREADABLE)
      val translation =
        info.translations.orEmpty().filterNotNull().firstOrNull { it.language == ENGLISH }
          ?: throw GuideNotFoundException("Liikkeestä ei ole englanninkielistä kuvausta.")
      val name =
        translation.name?.takeIf { it.isNotBlank() }
          ?: throw GuideUnavailableException(GuideHttp.UNREADABLE)

      // The main picture when one is marked, otherwise the first. Blank when there is none —
      // two thirds of wger's movements have no picture, and the instructions are still worth it.
      val images = info.images.orEmpty().filterNotNull()
      val image = images.firstOrNull { it.is_main == true } ?: images.firstOrNull()

      return ExerciseGuide(
        id = id,
        name = name,
        imageUrl = image?.image.orEmpty(),
        instructions = htmlToLines(translation.description),
        // name_en is often empty and the Latin name is what is actually there.
        targetMuscles =
          info.muscles.orEmpty().filterNotNull().mapNotNull { muscle ->
            muscle.name_en?.takeIf { it.isNotBlank() } ?: muscle.name?.takeIf { it.isNotBlank() }
          },
        equipment =
          info.equipment.orEmpty().filterNotNull().mapNotNull { it.name?.takeIf(String::isNotBlank) },
        attribution = attributionFor(info.license?.short_name, image?.license_author),
      )
    }

    /**
     * The credit line for one guide.
     *
     * CC-BY-SA and friends require naming the licence and the author, and wger's images each
     * carry their own — so "wger.de" alone would not discharge it.
     */
    internal fun attributionFor(licence: String?, imageAuthor: String?): String =
      buildString {
        append(ATTRIBUTION)
        licence?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        imageAuthor?.takeIf { it.isNotBlank() }?.let { append(" · kuva: $it") }
      }

    private val TAG = Regex("<[^>]+>")

    private val BLOCK_END = Regex("(?i)</(p|li|div|h[1-6])>|<br\\s*/?>")

    /**
     * wger writes its instructions as HTML paragraphs, not as numbered steps. The sheet numbers
     * them itself, so each block becomes one line and the markup goes.
     */
    internal fun htmlToLines(html: String?): List<String> {
      if (html.isNullOrBlank()) return emptyList()
      return html
        .replace(BLOCK_END, "\n")
        .replace(TAG, "")
        .let(::unescape)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    }

    private fun unescape(text: String): String =
      text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&rsquo;", "'")
  }
}

/**
 * `GET /api/v2/exerciseinfo/{id}/`. Every field nullable: this is a community-run service whose
 * rows are edited by hand, and a missing muscle name should cost a line of the sheet, not the
 * whole sheet.
 */
private data class ExerciseInfoDto(
  val id: Int? = null,
  val license: WgerLicenceDto? = null,
  val images: List<WgerImageDto?>? = null,
  val translations: List<WgerTranslationDto?>? = null,
  val muscles: List<WgerMuscleDto?>? = null,
  val equipment: List<WgerEquipmentDto?>? = null,
)

private data class WgerLicenceDto(val short_name: String? = null)

private data class WgerImageDto(
  val image: String? = null,
  val is_main: Boolean? = null,
  val license_author: String? = null,
)

private data class WgerTranslationDto(
  val name: String? = null,
  val description: String? = null,
  val language: Int? = null,
)

private data class WgerMuscleDto(val name: String? = null, val name_en: String? = null)

private data class WgerEquipmentDto(val name: String? = null)
