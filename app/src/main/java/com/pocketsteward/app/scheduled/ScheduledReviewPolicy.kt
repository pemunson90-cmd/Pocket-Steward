package com.pocketsteward.app.scheduled

import com.pocketsteward.app.data.db.FileRecord

/**
 * Pure selection boundary for a scheduled cleanup review.
 *
 * A scheduled notification is about files that arrived since the previous
 * scan. Re-opening it must not silently widen into "reorganize everything in
 * this root". Exact refs are therefore an allowlist, not merely a hint.
 */
object ScheduledReviewPolicy {
    fun selectNewFiles(
        records: List<FileRecord>,
        suggestion: PendingCleanupSuggestion,
    ): List<FileRecord> {
        if (suggestion.newFileRefs.isEmpty()) return emptyList()
        val allowed = suggestion.newFileRefs.toHashSet()
        return records
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { it.stableRef in allowed }
            .distinctBy { it.stableRef }
            .toList()
    }
}
