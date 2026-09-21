package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Test

class InventoryExportTest {
    @Test
    fun jsonAndCsvPreserveAwkwardNames() {
        val record = FileRecord(
            stableRef = "/Download/a,\"b\".txt",
            displayName = "a,\"b\".txt",
            extension = "txt",
            mimeType = "text/plain",
            absolutePathOrUri = "/Download/a,\"b\".txt",
            parentRef = "/Download",
            sizeBytes = 12,
            createdAt = null,
            modifiedAt = 123,
            lastScannedAt = 123,
            isDirectory = false,
            isHidden = false,
        )

        val json = InventoryExport.json("Download", listOf(record))
        val csv = InventoryExport.csv(listOf(record))

        assertThat(json).contains("a,\\\"b\\\".txt")
        assertThat(csv).contains("\"a,\"\"b\"\".txt\"")
    }
}
