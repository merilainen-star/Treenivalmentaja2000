fun main() {
    val desc = "Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Bulgarialainen askelkyykky 8/jalka, kahvakuulaheilautus 15, vinot vatsarutistukset 10/puoli, timanttipunnerrus 6–8, sivulankku 20 s/puoli. Tauko 30–45 s liikkeiden välissä."
    val sentences = desc.split(Regex("""(?<=\.)\s+"""))
    val exerciseSentence = sentences.maxByOrNull { it.count { c -> c == ',' } }
    println("Ex sentence: $exerciseSentence")
    if (exerciseSentence != null && exerciseSentence.count { it == ',' } > 0) {
        val intro = sentences.takeWhile { it != exerciseSentence }.joinToString(" ")
        val outro = sentences.takeLastWhile { it != exerciseSentence }.joinToString(" ")
        
        val exerciseStrings = exerciseSentence.removeSuffix(".").split(",")
        val exercises = exerciseStrings.map { ex -> 
            val name = ex.trim()
            val isPlank = name.lowercase().contains("lankku")
            var duration: Int? = null
            if (isPlank) {
                 val match = Regex("""(\d+)\s*s""").find(name.lowercase())
                 if (match != null) {
                     duration = match.groupValues[1].toIntOrNull()
                 }
            }
            "Name: $name, Plank: $isPlank, Duration: $duration"
        }
        println("Intro: $intro")
        println("Exercises:\n" + exercises.joinToString("\n"))
        println("Outro: $outro")
    }
}
