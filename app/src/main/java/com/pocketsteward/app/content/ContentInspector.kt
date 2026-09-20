package com.pocketsteward.app.content

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef

/**
 * On-demand local content search. No extracted text is persisted to Room.
 *
 * The per-search budget is deliberate. Even a read-only feature should not
 * turn a broad storage scan into gigabytes of surprise I/O.
 */
class ContentInspector(
    private val gateway: StorageGateway,
) {
    suspend fun search(
        records: List<FileRecord>,
        query: String,
        maxResults: Int = MAX_RESULTS,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): ContentSearchSummary {
        val needle = query.trim()
        require(needle.isNotBlank()) { "Content search term cannot be blank." }

        val candidates = records.filter { !it.isDirectory }
        val matches = mutableListOf<ContentMatch>()
        var inspected = 0
        var unsupported = 0
        var failed = 0
        var estimatedReadBytes = 0L
        var limited = false

        for ((index, record) in candidates.withIndex()) {
            onProgress(index, candidates.size)

            if (matches.size >= maxResults || inspected >= MAX_READABLE_FILES) {
                limited = true
                break
            }
            if (!ContentExtractor.supports(record.extension)) {
                unsupported++
                continue
            }
            if (record.sizeBytes > MAX_SOURCE_FILE_BYTES) {
                unsupported++
                continue
            }

            val estimatedCost = minOf(record.sizeBytes.coerceAtLeast(0L), ContentExtractor.MAX_TEXT_BYTES.toLong())
            if (estimatedReadBytes + estimatedCost > MAX_ESTIMATED_READ_BYTES) {
                limited = true
                break
            }
            estimatedReadBytes += estimatedCost

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
        onProgress(minOf(candidates.size, inspected + unsupported + failed), candidates.size)

        return ContentSearchSummary(
            matches = matches,
            inspectedFiles = inspected,
            unsupportedFiles = unsupported,
            failedFiles = failed,
            truncatedResults = limited,
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

    private companion object {
        const val MAX_RESULTS = 500
        const val MAX_READABLE_FILES = 2_000
        const val MAX_SOURCE_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_ESTIMATED_READ_BYTES = 128L * 1024 * 1024
    }
}
