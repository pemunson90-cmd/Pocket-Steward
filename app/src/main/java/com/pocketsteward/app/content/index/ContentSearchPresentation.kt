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

    fun mimeType(extension: String): String = when (extension.trim().lowercase()) {
        "pdf" -> "application/pdf"
        "txt", "md", "markdown", "log", "csv", "json", "xml", "yaml", "yml" -> "text/plain"
        "html", "htm" -> "text/html"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "*/*"
    }
}
