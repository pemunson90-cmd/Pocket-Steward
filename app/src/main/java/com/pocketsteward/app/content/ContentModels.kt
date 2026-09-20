package com.pocketsteward.app.content

import com.pocketsteward.app.data.db.FileRecord

enum class ContentKind {
    PLAIN_TEXT,
    OOXML,
}

sealed interface ContentExtraction {
    data class Text(
        val content: String,
        val truncated: Boolean,
        val kind: ContentKind,
    ) : ContentExtraction

    data class Unsupported(val reason: String) : ContentExtraction
    data class Failed(val reason: String) : ContentExtraction
}

data class ContentMatch(
    val record: FileRecord,
    val snippet: String,
)

data class ContentSearchSummary(
    val matches: List<ContentMatch>,
    val inspectedFiles: Int,
    val unsupportedFiles: Int,
    val failedFiles: Int,
    val truncatedResults: Boolean,
)
