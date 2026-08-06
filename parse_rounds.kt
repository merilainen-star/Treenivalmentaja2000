fun extractRounds(intro: String): Int {
    val match = Regex("""(\d+)\s*kierros""").find(intro.lowercase())
    return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
}

fun main() {
    println(extractRounds("Viikko 1/8. Lämmittely 2 min. 2 kierrosta."))
    println(extractRounds("3 kierrosta."))
    println(extractRounds("Ei kierroksia."))
}
