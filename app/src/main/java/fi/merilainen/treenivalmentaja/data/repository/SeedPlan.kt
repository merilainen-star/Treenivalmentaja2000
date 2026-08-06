package fi.merilainen.treenivalmentaja.data.repository

import java.time.LocalDate

/**
 * The starter week written on first launch.
 *
 * It is emitted as plan-schema-v1 JSON and goes through the very same importer as a user-supplied
 * file. That keeps one write path into Room, and means the seed is continuously validated against
 * the published schema instead of quietly drifting from it.
 */
internal object SeedPlan {

  const val PLAN_ID = "seed-aloitusviikko"

  private data class SeedSession(
    val id: String,
    val dayOffset: Int,
    val type: String,
    val time: String,
    val durationMin: Int,
    val description: String,
    val lighterMin: Int,
  )

  private val sessions =
    listOf(
      SeedSession("seed-1", 0, "STRENGTH", "09:00", 20, "Voima B – jalat ja core. Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Bulgarialainen askelkyykky 8/jalka, kahvakuulaheilautus 15, vinot vatsarutistukset 10/puoli, timanttipunnerrus 6–8, sivulankku 20 s/puoli. Tauko 30–45 s liikkeiden välissä.", 15),
      SeedSession("seed-2", 1, "STRENGTH", "09:00", 20, "Voima A – rinta ja keskivartalo. Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Punnerrus 10, goblet-kyykky 12, vatsarutistus penkillä 12, käsipainosoutu 10/puoli, lankku 30 s. Tauko 30–45 s liikkeiden välissä.", 15),
      SeedSession("seed-3", 2, "STRENGTH", "09:00", 15, "Kevyt voimaharjoittelu. Viikko 1/8. Lämmittely 2 min. 1–2 kierrosta. Kevyt punnerrus 8, kyykky 10, vatsarutistus 10, lankku 20 s. Ei loppuun asti. Tauko 30–45 s liikkeiden välissä.", 10),
      SeedSession("seed-4", 3, "STRENGTH", "09:00", 15, "Palauttava core ja liikkuvuus. Viikko 1/8. Lämmittely 2 min. 1 kierros. Kissanlehmä 8, lonkankoukistajan venytys 30 s/puoli, bird dog 8/puoli, lankku 20 s, sivulankku 15 s/puoli. Tauko 30–45 s liikkeiden välissä.", 10),
      SeedSession("seed-5", 4, "RUNNING", "12:00", 45, "Pitkä rauhallinen juoksu – 7 km. Viikko 1/8. Juokse 7 km kevyellä peruskestävyysvauhdilla. Pidä vauhti sellaisena, että pystyt puhumaan.", 30),
      SeedSession("seed-6", 5, "RUNNING", "12:00", 30, "Reippaampi juoksu – 5 km. Viikko 1/8. Yhteensä 5 km: 1 km rauhallisesti, keskiosa noin 5:25–5:35 min/km, viimeinen 1 km rauhallisesti.", 20),
      SeedSession("seed-7", 6, "STRENGTH", "09:00", 20, "Voima B – jalat ja core. Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Bulgarialainen askelkyykky 8/jalka, kahvakuulaheilautus 15, vinot vatsarutistukset 10/puoli, timanttipunnerrus 6–8, sivulankku 20 s/puoli. Tauko 30–45 s liikkeiden välissä.", 15),
      SeedSession("seed-8", 7, "RUNNING", "12:00", 30, "Kevyt juoksu – 5 km. Viikko 1/8. Juokse 5 km rauhallisesti. Tavoitevauhti noin 5:45–6:05 min/km.", 20),
    )

  fun json(startDate: LocalDate, timeZone: String): String {
    val sessionJson =
      sessions.joinToString(",\n") { session ->
        """
        {
          "id": "${session.id}",
          "type": "${session.type}",
          "date": "${startDate.plusDays(session.dayOffset.toLong())}",
          "time": "${session.time}",
          "durationMin": ${session.durationMin},
          "intensity": "${if (session.durationMin >= 60) "MODERATE" else "EASY"}",
          "description": "${session.description}",
          "lighterAlternative": {
            "durationMin": ${session.lighterMin},
            "intensity": "EASY",
            "description": "Kevyempi versio: ${session.description}"
          }
        }
        """
          .trimIndent()
      }

    return """
      {
        "schemaVersion": 1,
        "plan": {
          "id": "$PLAN_ID",
          "name": "Aloitusviikko",
          "timeZone": "$timeZone",
          "startDate": "$startDate",
          "description": "Esitäytetty viikko, jotta sovelluksessa on heti sisältöä. Voit korvata tämän tuomalla oman suunnitelman."
        },
        "weeks": [
          {
            "weekNumber": 1,
            "focus": "Peruskestävyys",
            "sessions": [
              $sessionJson
            ]
          }
        ]
      }
      """
      .trimIndent()
  }
}
