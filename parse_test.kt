data class ParsedWorkout(
    val intro: String,
    val exercises: List<ParsedExercise>,
    val outro: String
)

data class ParsedExercise(
    val name: String,
    val isPlank: Boolean,
    val plankDurationSeconds: Int?
)

fun extractDuration(text: String): Int? {
    val match = Regex("""(\d+)\s*s""").find(text.lowercase())
    return match?.groupValues?.get(1)?.toIntOrNull()
}

fun parseStrengthDescription(desc: String): ParsedWorkout {
    val cleanDesc = desc.replace("\\,", ",")
    val parts = cleanDesc.split(Regex("""(?<=\.)\s+|\n+""")).filter { it.isNotBlank() }
    
    val exerciseSentence = parts.maxByOrNull { it.count { c -> c == ',' } }
    
    if (exerciseSentence == null || exerciseSentence.count { it == ',' } == 0) {
        val exercises = parts.map { 
            ParsedExercise(it.trim(), it.lowercase().contains("lankku"), extractDuration(it)) 
        }
        return ParsedWorkout("", exercises, "")
    }
    
    val intro = parts.takeWhile { it != exerciseSentence }.joinToString(" ")
    val outro = parts.takeLastWhile { it != exerciseSentence }.joinToString(" ")
    
    val exerciseStrings = exerciseSentence.removeSuffix(".").split(",")
    val exercises = exerciseStrings.map { ex -> 
        val name = ex.trim()
        ParsedExercise(name, name.lowercase().contains("lankku"), extractDuration(name))
    }
    
    return ParsedWorkout(intro, exercises, outro)
}

fun main() {
    println(parseStrengthDescription("Viikko 1/8. Lämmittely 2 min. 2 kierrosta. Punnerrus 10, lankku 30 s. Tauko 30 s."))
    println(parseStrengthDescription("Aamun keskivartalo ja liikkuvuus."))
    println(parseStrengthDescription("1. Kyykky\n2. Vatsat\n3. Lankku 45 s"))
}
