package fi.merilainen.treenivalmentaja.domain

import java.util.Locale

/** Strip presentation metadata only; equipment and movement variants remain significant. */
object ExerciseNames {
  fun base(name: String): String = name.lowercase(Locale.ROOT).split('·')
    .map(String::trim)
    .filterNot { it in setOf("lämmittely", "loppuverryttely", "kevyt versio", "vasen", "oikea", "vasen jalka", "oikea jalka") || it.startsWith("sarja ") }
    .joinToString(" · ").replace(Regex("\\s+"), " ").trim()

  fun query(name: String): String = translations[base(name)] ?: base(name)

  // References already verified in docs/EXERCISE_GUIDE.md. No catalogue content is bundled.
  fun reference(name: String): GuideRef? = references[base(name)]

  private val references = mapOf(
    "punnerrus" to GuideRef("exercisedb", "I4hDWkc"),
    "goblet-kyykky" to GuideRef("exercisedb", "ZA8b5hc"),
    // Exact names checked against the public provider on 2026-09-27.
    "yhden käden kahvakuulasoutu" to GuideRef("exercisedb", "g9AsZ8P"),
    "pystypunnerrus käsipainoilla" to GuideRef("exercisedb", "A6wtbuL"),
    "dead bug" to GuideRef("exercisedb", "iny3m5y"),
    "askelkyykky paikallaan" to GuideRef("exercisedb", "9E25EOx"),
    "lankku" to GuideRef("wger", "458"),
    "lankku kyynärvarsilla" to GuideRef("wger", "458"),
    "sivulankku" to GuideRef("wger", "580"),
    "kehonpainokyykky" to GuideRef("wger", "615"),
    "bird dog" to GuideRef("wger", "1572"),
    "kissanlehmä" to GuideRef("wger", "1938"),
    "kissa-lehmä" to GuideRef("wger", "1938"),
    "lonkankoukistajan venytys" to GuideRef("wger", "1867"),
  )
  private val translations = mapOf(
    "yhden käden kahvakuulasoutu" to "kettlebell one arm row",
    "pystypunnerrus käsipainoilla" to "dumbbell standing shoulder press",
    "dead bug" to "dead bug",
    "askelkyykky paikallaan" to "split squat",
    "romanialainen maastaveto kahvakuulalla" to "kettlebell romanian deadlift",
    "yhden jalan pohjenousu" to "single leg calf raise",
    "kahden jalan lantionnosto" to "glute bridge",
    "lantion taakse vienti" to "hip hinge",
    "hartioiden pyöritys" to "shoulder circles",
    "reipas paikallaanmarssi" to "marching",
    "paikallaanmarssi" to "marching",
    "rauhallinen kävely" to "walking",
  )
}
