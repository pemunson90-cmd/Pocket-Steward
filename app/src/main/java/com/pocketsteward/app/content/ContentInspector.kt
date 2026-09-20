package com.pocketsteward.app.content

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.parseFileRef
import kotlinx.coroutines.CancellationException

/**
 * On-demand local content search. No extracted text is persisted to Room.
 */
class ContentInspector(
    private val gateway: StorageGateway,
    private val pdfExtractor: PdfContentExtractor? = null,
) {
    suspend fun extract(record: FileRecord): ContentExtraction {
        if (record.isDirectory) return ContentExtraction.Unsupported("Directories do not have inspectable content.")
        if (!ContentExtractor.supports(record.extension)) {
            return ContentExtraction.Unsupported("This file type is not text-readable.")
        }
        if (record.sizeBytes > MAX_SOURCE_FILE_BYTES) {
            return ContentExtraction.Unsupported("File exceeds the 20 MiB inspection limit.")
        }
        return try {
            gateway.openRead(parseFileRef(record.stableRef)).use { input ->
                if (record.extension.equals("pdf", ignoreCase = true)) {
                    pdfExtractor?.extract(input)
                        ?: ContentExtraction.Unsupported("PDF extraction is unavailable on this device.")
                } else {
                    ContentExtractor.extract(record.extension, input)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            ContentExtraction.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

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
        var processed = 0

        for (record in candidates) {
            onProgress(processed, candidates.size)

            if (matches.size >= maxResults || inspected >= MAX_READABLE_FILES) {
                limited = true
                break
            }
            if (!ContentExtractor.supports(record.extension) || record.sizeBytes > MAX_SOURCE_FILE_BYTES) {
                unsupported++
                processed++
                continue
            }

            val estimatedCost = minOf(record.sizeBytes.coerceAtLeast(0L), ContentExtractor.MAX_TEXT_BYTES.toLong())
            if (estimatedReadBytes + estimatedCost > MAX_ESTIMATED_READ_BYTES) {
                limited = true
                break
            }
            estimatedReadBytes += estimatedCost

            when (val extraction = extract(record)) {
                is ContentExtraction.Text -> {
                    inspected++
                    val pageMatch = extraction.pages.firstNotNullOfOrNull { page ->
                        val index = page.text.indexOf(needle, ignoreCase = true)
                        if (index >= 0) Triple(page, index, needle.length) else null
                    }
                    if (pageMatch != null) {
                        val (page, index, length) = pageMatch
                        matches += ContentMatch(
                            record = record,
                            snippet = snippetAround(page.text, index, length),
                            pageNumber = page.pageNumber,
                            ocr = page.ocr,
                        )
                    } else {
                        val matchIndex = extraction.content.indexOf(needle, ignoreCase = true)
                        if (matchIndex >= 0) {
                            matches += ContentMatch(
                                record = record,
                                snippet = snippetAround(extraction.content, matchIndex, needle.length),
                            )
                        }
                    }
                }
                is ContentExtraction.Unsupported -> unsupported++
                is ContentExtraction.Failed -> failed++
            }
            processed++
        }
        onProgress(processed, candidates.size)

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
