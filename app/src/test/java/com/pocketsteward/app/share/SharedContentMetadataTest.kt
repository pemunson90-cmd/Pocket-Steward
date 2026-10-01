package com.pocketsteward.app.share

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class SharedContentMetadataTest {
    private val ref = FileRef.Saf("content://media/external/images/media/42")
    @Test fun ordinaryMediaProviderColumnsDescribeReadableFileWithoutDocumentId() {
        val record = SharedContentMetadata.build(ref, "cover.jpg", 1280, null, "image/jpeg", "shared-file.jpg")
        assertThat(record.displayName).isEqualTo("cover.jpg")
        assertThat(record.extension).isEqualTo("jpg")
        assertThat(record.sizeBytes).isEqualTo(1280)
        assertThat(record.isDirectory).isFalse()
    }
    @Test fun descriptorSizeCanReplaceMissingOpenableSize() {
        assertThat(SharedContentMetadata.build(ref, null, null, 150, "image/jpeg", "shared-file.jpg").sizeBytes).isEqualTo(150)
    }
    @Test fun unknownSizeMustNotMasqueradeAsEmptyFile() {
        assertThat(SharedContentMetadata.build(ref, null, null, -1, null, "shared-file").sizeBytes).isEqualTo(-1)
    }
    @Test fun directoryMimePreventsFolderBeingOfferedAsFileCopy() {
        assertThat(SharedContentMetadata.build(ref, "folder", 0, null, "vnd.android.document/directory", "shared-file").isDirectory).isTrue()
    }
}
