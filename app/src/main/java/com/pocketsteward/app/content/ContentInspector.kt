package com.pocketsteward.app.content

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef

/**
 * On-demand local content search. No extracted text is persisted to Room.
 */
class ContentInspector(
    private val gateway: StorageGateway,
) {
    suspend fun search(
        records: List<FileRecord>,
        query: String,
        maxResults: Int = 500,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): ContentSearchSummary {
        val needle = query.trim()
        require(needle.isNotBlank()) { "Content search term cannot be blank." }

        val candidates = records.filter { !it.isDirectory }
        val matches = mutableListOf<ContentMatch>()
        var inspected = 0
        var unsupported = 0
        var failed = 0
        var truncatedResults = false

        candidates.forEachIndexed { index, record ->
            onProgress(index, candidates.size)
            if (!ContentExtractor.supports(record.extension)) {
                unsupported++
                return@forEachIndexed
            }

            val extraction = try {
                gateway.openRead(parseFileRef(record.stableRef)).use { input ->
                    ContentExtractor.extract(record.extension, input)
                }
            } catch (t: Throwable) {
                ContentExtraction.Failed(t.message ?: t.javaClass.simpleName)
            }

            when (extraction) {
                is ContentExtraction.Text -> {
                    inspected++
                    val matchIndex = extraction.content.indexOf(needle, ignoreCase = true)
                    if (matchIndex >= 0) {
                        if (matches.size >= maxResults) {
                            truncatedResults = true
                            return@forEachIndexed
                        }
                        matches += ContentMatch(
                            record = record,
                            snippet = snippetAround(extraction.content, matchIndex, needle.length),
                        )
                    }
                }
                is ContentExtraction.Unsupported -> unsupported++
                is ContentExtraction.Failed -> failed++
            }
        }
        onProgress(candidates.size, candidates.size)

        return ContentSearchSummary(
            matches = matches,
            inspectedFiles = inspected,
            unsupportedFiles = unsupported,
            failedFiles = failed,
            truncatedResults = truncatedResults,
        )
    }

    private fun snippetAround(text: String, matchStart: Int, matchLength: Int): String {
        val radius = 90
        val start = maxOf(0, matchStart - radius)
        val end = minOf(text.length, matchStart + matchLength + radius)
        val body = text.substring(start, end).replace(Regex("""\s+"""), " ").trim()
        return buildString {
            if (start > 0) append("…")
            append(body)
            if (end < text.length) append("…")
        }
    }
}
