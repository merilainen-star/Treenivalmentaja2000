fun main() {
    val descs = listOf(
        "Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Bulgarialainen askelkyykky 8/jalka\\, kahvakuulaheilautus 15\\, vinot vatsarutistukset 10/puoli\\, timanttipunnerrus 6–8\\, sivulankku 20 s/puoli. Tauko 30–45 s liikkeiden välissä.",
        "Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Punnerrus 10, goblet-kyykky 12, vatsarutistus penkillä 12, käsipainosoutu 10/puoli, lankku 30 s. Tauko 30–45 s liikkeiden välissä."
    )
    for (desc in descs) {
        val cleanDesc = desc.replace("\\,", ",")
        val sentences = cleanDesc.split(Regex("""(?<=\.)\s+"""))
        val exerciseSentence = sentences.maxByOrNull { it.count { c -> c == ',' } }
        println("Desc: $desc")
        println("Ex sentence: $exerciseSentence")
        if (exerciseSentence == null || exerciseSentence.count { it == ',' } == 0) {
            println("No exercises parsed.")
        } else {
            println("Exercises parsed.")
        }
    }
}
