package fi.merilainen.treenivalmentaja.domain

/**
 * Which of the two colour schemes the app draws itself in, or whether it lets the phone decide.
 *
 * **Three options rather than a switch.** A two-state "tumma tila" toggle has no way to say "follow
 * the system", which is what the app did before this preference existed and what most people want
 * kept: a phone that turns dark at sunset should take the app with it. Adding a switch would have
 * meant choosing, silently and permanently, that it no longer does.
 *
 * The stored value is the **enum constant name**, the same as [AnalysisModel] stores its own, and
 * for the same reason — a preference should survive the list being reordered or an option being
 * added, and anything unrecognised reads as [DEFAULT] rather than throwing. A corrupted preference
 * should hand the decision back to the system, not leave Settings unable to draw.
 */
enum class ThemePreference(val label: String, val detail: String) {
  LIGHT("Vaalea", "Aina vaalea, riippumatta puhelimen asetuksesta"),
  DARK("Tumma", "Aina tumma, riippumatta puhelimen asetuksesta"),
  SYSTEM("Järjestelmä", "Seuraa puhelimen tumman tilan asetusta");

  companion object {

    /**
     * Following the system, because that is what the app did before the preference existed.
     *
     * A default of anything else would change the appearance of every existing install on update,
     * which is not something a new setting should do to someone who never opens it.
     */
    val DEFAULT: ThemePreference = SYSTEM

    /** The stored preference back into an enum. Anything unrecognised reads as [DEFAULT]. */
    fun fromStored(stored: String?): ThemePreference =
      entries.firstOrNull { it.name == stored } ?: DEFAULT
  }
}
