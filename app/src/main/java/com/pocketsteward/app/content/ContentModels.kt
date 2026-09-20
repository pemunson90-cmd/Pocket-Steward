package com.pocketsteward.app.content

import com.pocketsteward.app.data.db.FileRecord

enum class ContentKind {
    PLAIN_TEXT,
    OOXML,
    PDF_TEXT,
    PDF_OCR,
}

data class PageTextSegment(
    val pageNumber: Int,
    val text: String,
    val ocr: Boolean,
)

sealed interface ContentExtraction {
    data class Text(
        val content: String,
        val truncated: Boolean,
        val kind: ContentKind,
        val pages: List<PageTextSegment> = emptyList(),
    ) : ContentExtraction

    data class Unsupported(val reason: String) : ContentExtraction
    data class Failed(val reason: String) : ContentExtraction
}

data class ContentMatch(
    val record: FileRecord,
    val snippet: String,
    val pageNumber: Int? = null,
    val ocr: Boolean = false,
)

data class ContentSearchSummary(
    val matches: List<ContentMatch>,
    val inspectedFiles: Int,
    val unsupportedFiles: Int,
    val failedFiles: Int,
    val truncatedResults: Boolean,
)
