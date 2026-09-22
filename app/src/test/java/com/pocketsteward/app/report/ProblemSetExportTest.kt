package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Test

class ProblemSetExportTest {
    @Test
    fun exportContainsMetadataButNoContentField() {
        val record = FileRecord(
            stableRef = "/Download/mystery.xyz",
            displayName = "mystery.xyz",
            extension = "xyz",
            mimeType = null,
            absolutePathOrUri = "/Download/mystery.xyz",
            parentRef = "/Download",
            sizeBytes = 123,
            createdAt = null,
            modifiedAt = 456,
            lastScannedAt = 789,
            isDirectory = false,
            isHidden = false,
            textPreview = "private preview that must not be exported",
        )

        val markdown = ProblemSetExport.markdown("Downloads", listOf(record))
        val json = ProblemSetExport.json("Downloads", listOf(record))

        assertThat(markdown).contains("mystery.xyz")
        assertThat(markdown).contains("metadata only")
        assertThat(markdown).doesNotContain("private preview")
        assertThat(json).contains("\"contentsIncluded\": false")
        assertThat(json).doesNotContain("private preview")
    }
}
