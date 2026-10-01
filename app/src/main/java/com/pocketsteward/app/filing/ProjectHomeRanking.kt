package com.pocketsteward.app.filing

/** Stable suggestions; ranking never selects or authorizes a destination. */
object ProjectHomeRanking {
    fun rank(homes: List<ProjectHomeCandidate>, query: String): List<ProjectHomeCandidate> {
        val text = query.trim().lowercase()
        fun score(home: ProjectHomeCandidate): Int {
            val names = listOf(home.name) + home.aliases
            val match = when {
                text.isEmpty() -> 0
                names.any { it.equals(text, true) } -> 100
                names.any { it.startsWith(text, true) } -> 60
                names.any { it.contains(text, true) } || home.path.contains(text, true) -> 30
                else -> -100
            }
            return match + if (home.persisted) 10 else 0
        }
        return homes.distinctBy { it.path }.filter { text.isEmpty() || score(it) >= 0 }
            .sortedWith(compareByDescending<ProjectHomeCandidate> { score(it) }.thenBy { it.name.lowercase() }.thenBy { it.path })
    }
}
