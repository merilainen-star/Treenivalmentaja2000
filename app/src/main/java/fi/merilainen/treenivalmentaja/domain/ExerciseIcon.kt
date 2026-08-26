package fi.merilainen.treenivalmentaja.domain

/**
 * The stick figure drawn beside a movement's name.
 *
 * These are **category** marks, not per-movement illustration. The movement's own picture is
 * fetched at runtime from ExerciseDB or wger (see `docs/EXERCISE_GUIDE.md`); this is the thing that
 * is in the APK, works offline, tints with the theme, and only has to answer "what shape is my body
 * in". Two movements that put the body in the same place share an icon on purpose — a plank and a
 * push-up differ by one forearm, and the icons differ by one forearm.
 *
 * [GENERIC] is not a failure. A plan may name anything at all, and an icon that guesses wrong is
 * worse than one that stays out of the way.
 */
enum class ExerciseIcon {
  PLANK,
  PUSHUP,
  SIDE_PLANK,
  SQUAT,
  LUNGE,
  STRETCH,
  ROW,
  SWING,
  CRUNCH,
  QUADRUPED,
  BIRD_DOG,
  GENERIC;

  companion object {
    /**
     * Matched on the Finnish name the plan carries, because that is the only thing every movement
     * has — `guide` is optional and its catalogue ids are English and not always present.
     *
     * Order matters. The list is read top to bottom and the first match wins, so the specific
     * qualifier comes before the family it belongs to: *sivulankku* before *lankku*,
     * *bird dog* before the four-point position it is a variation of. Reversing any of these pairs
     * would silently give the general icon to the special case.
     */
    private val RULES: List<Pair<String, ExerciseIcon>> = listOf(
      "sivulankku" to SIDE_PLANK,
      "kylkilankku" to SIDE_PLANK,
      "bird dog" to BIRD_DOG,
      "birddog" to BIRD_DOG,
      "lankku" to PLANK,
      "punnerrus" to PUSHUP,
      "askelkyykky" to LUNGE,
      "etunojapunnerrus" to PUSHUP,
      "kyykky" to SQUAT,
      "heilautus" to SWING,
      "swing" to SWING,
      "soutu" to ROW,
      "veto" to ROW,
      "rutistus" to CRUNCH,
      "vatsa" to CRUNCH,
      "kissanlehmä" to QUADRUPED,
      "kissa-lehmä" to QUADRUPED,
      "nelinkontin" to QUADRUPED,
      "venytys" to STRETCH,
      "avaus" to STRETCH,
    )

    fun forName(name: String): ExerciseIcon {
      val n = name.lowercase()
      return RULES.firstOrNull { (needle, _) -> needle in n }?.second ?: GENERIC
    }
  }
}
