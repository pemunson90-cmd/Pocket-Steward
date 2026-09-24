package com.pocketsteward.app.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanRecordMergeTest {
    @Test
    fun anOlderWalkNeverStampsAFileBackwards() {
        val existing = record(id = 7, size = 100, modified = 1234).copy(lastScannedAt = 5_000)
        val olderWalk = record(id = 0, size = 100, modified = 1234).copy(lastScannedAt = 3_000)
        assertThat(mergeScanRecord(existing, olderWalk).lastScannedAt).isEqualTo(5_000)
        val changed = record(id = 0, size = 200, modified = 9999).copy(lastScannedAt = 3_000)
        assertThat(mergeScanRecord(existing, changed).lastScannedAt).isEqualTo(5_000)
    }

    @Test
    fun unchangedContentPreservesExpensiveDerivedFields() {
        val existing = record(
            id = 42,
            size = 100,
            modified = 1234,
        ).copy(
            width = 1920,
            height = 1080,
            durationMs = 99_000,
            apkPackageName = "com.example.app",
            apkVersionName = "1.2.3",
            sha256 = "abc",
            quickFingerprint = "quick",
            textPreview = "preview",
            classification = "DOCUMENT",
            classificationConfidence = 0.9f,
        )
        val scanned = record(
            id = 0,
            size = 100,
            modified = 1234,
        ).copy(lastScannedAt = 9_999)

        val merged = mergeScanRecord(existing, scanned)

        assertThat(merged.id).isEqualTo(42)
        assertThat(merged.lastScannedAt).isEqualTo(9_999)
        assertThat(merged.sha256).isEqualTo("abc")
        assertThat(merged.textPreview).isEqualTo("preview")
        assertThat(merged.width).isEqualTo(1920)
        assertThat(merged.apkPackageName).isEqualTo("com.example.app")
    }

    @Test
    fun changedSizeInvalidatesExpensiveDerivedFields() {
        val existing = record(
            id = 42,
            size = 100,
            modified = 1234,
        ).copy(
            sha256 = "old-hash",
            quickFingerprint = "old-quick",
            textPreview = "old-text",
            width = 640,
            height = 480,
            classification = "DOCUMENT",
            classificationConfidence = 1.0f,
        )
        val scanned = record(
            id = 0,
            size = 101,
            modified = 1234,
        )

        val merged = mergeScanRecord(existing, scanned)

        assertThat(merged.id).isEqualTo(42)
        assertThat(merged.sha256).isNull()
        assertThat(merged.quickFingerprint).isNull()
        assertThat(merged.textPreview).isNull()
        assertThat(merged.width).isNull()
        assertThat(merged.classification).isNull()
    }

    @Test
    fun changedModifiedTimeInvalidatesCachedContentEvidence() {
        val existing = record(
            id = 7,
            size = 50,
            modified = 1000,
        ).copy(
            sha256 = "hash",
            textPreview = "cached",
        )
        val scanned = record(
            id = 0,
            size = 50,
            modified = 1001,
        )

        val merged = mergeScanRecord(existing, scanned)

        assertThat(merged.id).isEqualTo(7)
        assertThat(merged.sha256).isNull()
        assertThat(merged.textPreview).isNull()
    }

    private fun record(
        id: Long,
        size: Long,
        modified: Long?,
    ): FileRecord = FileRecord(
        id = id,
        stableRef = "/storage/emulated/0/Download/file.txt",
        displayName = "file.txt",
        extension = "txt",
        mimeType = "text/plain",
        absolutePathOrUri = "/storage/emulated/0/Download/file.txt",
        parentRef = "/storage/emulated/0/Download",
        sizeBytes = size,
        createdAt = null,
        modifiedAt = modified,
        lastScannedAt = 1,
        isDirectory = false,
        isHidden = false,
    )
}
