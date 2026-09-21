package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class CompositeFileIndexTest {
    @Test
    fun crossRootMoveValidatesOnlyWhenDestinationRootIsExplicitlyComposed() {
        val sourceRoot = FileRef.Direct("/Download")
        val source = FileRef.Direct("/Download/a.txt")
        val destinationRoot = FileRef.Direct("/Documents")
        val destination = FileRef.Direct("/Documents/Notes/a.txt")

        val sourceIndex = InMemoryFileIndex(
            listOf(
                record("/Download", "", true),
                record("/Download/a.txt", "/Download", false),
            ),
        )
        val destinationIndex = SingleFolderIndex(destinationRoot, emptyList())
        val operations = listOf(
            PlannedOperation.CreateDirectory(destinationRoot, "Notes", "approved group"),
            PlannedOperation.Move(source, destination, "approved move"),
        )

        val sourceOnly = PlanValidator.validate(operations, sourceIndex)
        val composite = PlanValidator.validate(
            operations,
            CompositeFileIndex(listOf(sourceIndex, destinationIndex)),
        )

        assertThat(sourceOnly.accepted).isEmpty()
        assertThat(composite.accepted).hasSize(2)
    }

    private fun record(path: String, parent: String, directory: Boolean) = FileRecord(
        stableRef = path,
        displayName = path.substringAfterLast('/').ifBlank { "Download" },
        extension = if (directory) "" else "txt",
        mimeType = null,
        absolutePathOrUri = path,
        parentRef = parent.ifBlank { null },
        sizeBytes = if (directory) 0 else 1,
        createdAt = null,
        modifiedAt = null,
        lastScannedAt = 1,
        isDirectory = directory,
        isHidden = false,
    )
}
