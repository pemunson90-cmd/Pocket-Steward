package com.pocketsteward.app.ai

/**
 * What an answer is allowed to be. The model is told to answer only from
 * numbered excerpts and cite them as [n]; this is where that is enforced
 * rather than trusted.
 */
sealed interface AskModelAnswer {
    /** [citations] are passage numbers that actually exist, in first-cited order. */
    data class Answered(val text: String, val citations: List<Int>) : AskModelAnswer

    /** The model said the excerpts do not contain the answer. */
    data object NotFound : AskModelAnswer
}

object AskAnswerProtocol {
    const val NOT_FOUND_TOKEN = "NOT_FOUND"
    const val MAX_ANSWER_CHARS = 1_500

    private val citation = Regex("""\[(\d{1,2})(?:\s*[,;]\s*(\d{1,2}))*\]""")

    /**
     * Keeps the answer only if it cites at least one real passage. Citations
     * to passages that do not exist are removed from the text, and an answer
     * left with no valid citation is treated as unsupported: an uncited
     * claim about Pat's own files is exactly what this feature must not
     * produce.
     */
    fun parse(raw: String, passageCount: Int): AskModelAnswer? {
        val text = raw.trim().removeSurrounding("\"").trim()
        if (text.isEmpty()) return null
        if (text.startsWith(NOT_FOUND_TOKEN, ignoreCase = true) || text.equals("not found", ignoreCase = true)) {
            return AskModelAnswer.NotFound
        }

        val cited = linkedSetOf<Int>()
        val cleaned = citation.replace(text) { match ->
            val numbers = Regex("""\d{1,2}""").findAll(match.value).map { it.value.toInt() }
                .filter { it in 1..passageCount }
                .toList()
            cited += numbers
            if (numbers.isEmpty()) "" else numbers.joinToString(prefix = "[", postfix = "]", separator = "][")
        }.replace(Regex("""[ \t]+([.,;:])"""), "$1")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()

        if (cited.isEmpty() || cleaned.isBlank()) return null
        return AskModelAnswer.Answered(cleaned.take(MAX_ANSWER_CHARS), cited.toList())
    }
}
