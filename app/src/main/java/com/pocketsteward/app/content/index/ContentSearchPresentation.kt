package com.pocketsteward.app.content.index

/**
 * Tiny pure presentation rules shared by the indexed-search UI and tests.
 * Keeping these outside Compose makes the user-visible labels deterministic.
 */
object ContentSearchPresentation {
    fun filtersLabel(activeCount: Int): String =
        if (activeCount <= 0) "Filters" else "Filters ($activeCount)"

    fun quotedSnippet(text: String): String {
        val normalized = text.trim()
        return if (normalized.isEmpty()) "“”" else "“$normalized”"
    }
}
