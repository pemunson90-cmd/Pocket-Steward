package com.pocketsteward.app.scheduled

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Test

class ScheduledReviewPolicyTest {
    @Test
    fun olderFilesAreNeverPulledIntoScheduledReview() {
        val old = record("/Download/old.pdf", "old.pdf")
        val new = record("/Download/new.pdf", "new.pdf")
        val suggestion = PendingCleanupSuggestion(
            createdAtEpochMs = 10L,
            roots = listOf("/Download"),
            newFileCount = 1,
            obviousMatchCount = 1,
            newFileRefs = listOf(new.stableRef),
        )

        assertThat(ScheduledReviewPolicy.selectNewFiles(listOf(old, new), suggestion))
            .containsExactly(new)
    }

    @Test
    fun staleMissingRefsDoNotInventRecords() {
        val existing = record("/Download/other.pdf", "other.pdf")
        val suggestion = PendingCleanupSuggestion(
            createdAtEpochMs = 10L,
            roots = listOf("/Download"),
            newFileCount = 1,
            obviousMatchCount = 1,
            newFileRefs = listOf("/Download/missing.pdf"),
        )

        assertThat(ScheduledReviewPolicy.selectNewFiles(listOf(existing), suggestion)).isEmpty()
    }

    @Test
    fun duplicateRefsCannotDuplicateAPlanCandidate() {
        val new = record("/Download/new.pdf", "new.pdf")
        val suggestion = PendingCleanupSuggestion(
            createdAtEpochMs = 10L,
            roots = listOf("/Download"),
            newFileCount = 2,
            obviousMatchCount = 1,
            newFileRefs = listOf(new.stableRef, new.stableRef),
        )

        assertThat(ScheduledReviewPolicy.selectNewFiles(listOf(new, new), suggestion))
            .containsExactly(new)
    }

    private fun record(path: String, name: String) = FileRecord(
        stableRef = path,
        displayName = name,
        extension = name.substringAfterLast('.', ""),
        mimeType = "application/pdf",
        absolutePathOrUri = path,
        parentRef = "/Download",
        sizeBytes = 1L,
        createdAt = null,
        modifiedAt = 1L,
        lastScannedAt = 1L,
        isDirectory = false,
        isHidden = false,
    )
}
