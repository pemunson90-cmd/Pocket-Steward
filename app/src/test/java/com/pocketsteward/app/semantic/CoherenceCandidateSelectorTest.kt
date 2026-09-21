package com.pocketsteward.app.semantic

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import org.junit.Test

class CoherenceCandidateSelectorTest {
    @Test
    fun selectionRoundRobinsAcrossParentsAndExtensions() {
        val records = listOf(
            record("/Download/a.md", "/Download", "md", 30),
            record("/Download/b.md", "/Download", "md", 20),
            record("/Download/Docs/c.pdf", "/Download/Docs", "pdf", 25),
            record("/Download/Docs/d.pdf", "/Download/Docs", "pdf", 15),
            record("/Download/Notes/e.txt", "/Download/Notes", "txt", 10),
        )

        val selected = CoherenceCandidateSelector.select(records, maxDocuments = 3)

        assertThat(selected.map { it.stableRef }).containsExactly(
            "/Download/a.md",
            "/Download/Docs/c.pdf",
            "/Download/Notes/e.txt",
        )
    }

    @Test
    fun selectionIsBoundedAndSkipsUnsupportedTypes() {
        val records = (1..70).map { index ->
            record("/Download/$index.md", "/Download", "md", index.toLong())
        } + record("/Download/app.apk", "/Download", "apk", 100)

        val selected = CoherenceCandidateSelector.select(records)

        assertThat(selected).hasSize(CoherenceCandidateSelector.DEFAULT_MAX_DOCUMENTS)
        assertThat(selected.none { it.extension == "apk" }).isTrue()
    }

    private fun record(
        ref: String,
        parent: String,
        extension: String,
        modified: Long,
    ) = FileRecord(
        stableRef = ref,
        displayName = ref.substringAfterLast('/'),
        extension = extension,
        mimeType = null,
        absolutePathOrUri = ref,
        parentRef = parent,
        sizeBytes = 100,
        createdAt = null,
        modifiedAt = modified,
        lastScannedAt = modified,
        isDirectory = false,
        isHidden = false,
    )
}
