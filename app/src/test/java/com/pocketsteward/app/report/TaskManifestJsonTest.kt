package com.pocketsteward.app.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskManifestJsonTest {
    @Test
    fun jsonContainsJournalFactsAndEscapesText() {
        val document = TaskManifestDocument(
            taskRunId = 7,
            title = "Move \"docs\"",
            text = "human markdown",
            assertion = DuplicateAssertion(0, 0, 0),
            entries = listOf(
                ManifestEntry(
                    sequence = 0,
                    operation = "MOVE",
                    originalPath = "/Download/a.txt",
                    resultPath = "/Documents/a.txt",
                    fingerprint = null,
                    succeeded = true,
                    error = null,
                    reason = "organize",
                ),
            ),
        )

        val json = TaskManifestJson.render(document)

        assertThat(json).contains("\"taskRunId\": 7")
        assertThat(json).contains("Move \\\"docs\\\"")
        assertThat(json).contains("/Documents/a.txt")
    }
}
