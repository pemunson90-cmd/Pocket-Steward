package com.pocketsteward.app.filing

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class FilingFreshnessTest {
    @Test fun changedOrUnknownModificationInvalidatesDerivedEvidence() {
        val record = record()
        for (modified in listOf(2L, null)) {
            val updated = FilingFreshness.refresh(record, live(modified))
            assertThat(updated.apkPackageName).isNull()
            assertThat(updated.textPreview).isNull()
            assertThat(updated.sha256).isNull()
            assertThat(updated.width).isNull()
        }
    }
    @Test fun unchangedSourceKeepsReusableEvidence() {
        assertThat(FilingFreshness.refresh(record(), live(1)).apkPackageName).isEqualTo("old.package")
    }
    @Test(expected = IllegalArgumentException::class) fun changedTypeRequiresFreshScan() {
        FilingFreshness.refresh(record(), live(1).copy(isDirectory = true))
    }
    private fun record() = FileRecord(stableRef = "/inbox/a.apk", displayName = "a.apk", extension = "apk", mimeType = null, absolutePathOrUri = "/inbox/a.apk", parentRef = "/inbox", sizeBytes = 10, createdAt = null, modifiedAt = 1, lastScannedAt = 1, isDirectory = false, isHidden = false, apkPackageName = "old.package", textPreview = "old text", sha256 = "old hash", width = 100)
    private fun live(modified: Long?) = FileMetadata(FileRef.Direct("/inbox/a.apk"), "a.apk", "apk", null, 10, null, modified, false, false)
}
