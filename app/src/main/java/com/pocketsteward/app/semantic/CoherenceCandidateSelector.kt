package com.pocketsteward.app.semantic

import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.data.db.FileRecord

/**
 * Deterministic, stratified sampler for semantic audits.
 *
 * Large folders are never represented by "the first N paths". Files are
 * bucketed by parent + extension and round-robined, with recently modified
 * records first inside each bucket. This gives Nano a cross-section of the
 * actual scope while keeping on-device inference bounded.
 */
object CoherenceCandidateSelector {
    const val DEFAULT_MAX_DOCUMENTS = 48

    fun select(
        records: List<FileRecord>,
        maxDocuments: Int = DEFAULT_MAX_DOCUMENTS,
    ): List<FileRecord> {
        if (maxDocuments <= 0) return emptyList()

        val readable = records
            .asSequence()
            .filter { !it.isDirectory && ContentExtractor.supports(it.extension) }
            .distinctBy { it.stableRef }
            .toList()

        val buckets = readable
            .groupBy { record ->
                "${record.parentRef.orEmpty()}\u0000${record.extension.lowercase()}"
            }
            .values
            .map { bucket ->
                bucket.sortedWith(
                    compareByDescending<FileRecord> { it.modifiedAt ?: Long.MIN_VALUE }
                        .thenBy { it.displayName.lowercase() }
                        .thenBy { it.stableRef },
                )
            }
            .sortedByDescending { it.size }

        val selected = mutableListOf<FileRecord>()
        var round = 0
        while (selected.size < maxDocuments) {
            var added = false
            for (bucket in buckets) {
                val record = bucket.getOrNull(round) ?: continue
                selected += record
                added = true
                if (selected.size >= maxDocuments) break
            }
            if (!added) break
            round++
        }
        return selected
    }
}
