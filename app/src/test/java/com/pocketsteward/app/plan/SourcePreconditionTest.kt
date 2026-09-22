package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class SourcePreconditionTest {
    @Test
    fun exactMetadataMatches() {
        val expected = SourcePrecondition(100L, 200L)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(100L, 200L))).isTrue()
    }

    @Test
    fun sizeChangeFailsClosed() {
        val expected = SourcePrecondition(100L, 200L)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(101L, 200L))).isFalse()
    }

    @Test
    fun modificationTimeChangeFailsClosedWhenKnown() {
        val expected = SourcePrecondition(100L, 200L)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(100L, 201L))).isFalse()
    }

    @Test
    fun unknownApprovedModificationTimeFallsBackToSize() {
        val expected = SourcePrecondition(100L, null)
        assertThat(SourcePreconditions.matches(expected, SourcePrecondition(100L, 999L))).isTrue()
    }

    @Test
    fun directoryMetadataDoesNotCreateAFilePrecondition() {
        val metadata = FileMetadata(
            ref = FileRef.Direct("/sd/Folder"),
            displayName = "Folder",
            extension = "",
            mimeType = null,
            sizeBytes = 0L,
            createdAtEpochMs = null,
            modifiedAtEpochMs = 123L,
            isDirectory = true,
            isHidden = false,
        )
        assertThat(SourcePreconditions.from(metadata)).isNull()
    }

    @Test
    fun indexedFileRecordBecomesApprovalPrecondition() {
        val record = FileRecord(
            stableRef = "/sd/a.txt",
            displayName = "a.txt",
            extension = "txt",
            mimeType = "text/plain",
            absolutePathOrUri = "/sd/a.txt",
            parentRef = "/sd",
            sizeBytes = 55L,
            createdAt = null,
            modifiedAt = 77L,
            lastScannedAt = 88L,
            isDirectory = false,
            isHidden = false,
        )
        assertThat(SourcePreconditions.from(record)).isEqualTo(SourcePrecondition(55L, 77L))
    }
}
