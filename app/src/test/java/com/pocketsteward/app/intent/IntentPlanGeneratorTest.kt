package com.pocketsteward.app.intent

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class IntentPlanGeneratorTest {
    private val root = FileRef.Direct("/storage/emulated/0/Download")

    @Test fun destinationPreferenceChangesGroupsWhileSourcePartitionAndProtectionStayLocal() {
        val image = record("${root.absolutePath}/photo.jpg", "photo.jpg", "jpg")
        val protected = image.copy(stableRef = "${root.absolutePath}/protected/other.jpg", parentRef = "${root.absolutePath}/protected", displayName = "other.jpg")
        val marker = image.copy(stableRef = "${root.absolutePath}/protected/POCKETSTEWARD-DO-NOT-SORT.md", parentRef = protected.parentRef,
            displayName = "POCKETSTEWARD-DO-NOT-SORT.md", extension = "md")
        val outside = image.copy(stableRef = "/elsewhere/image.jpg", parentRef = "/elsewhere")
        val destination = FileRef.Direct("/storage/emulated/0/Documents")
        val intent = BoundedIntent(IntentAction.ORGANIZE, "organize images", includeSubfolders = true)
        val generated = IntentPlanGenerator.generate(root, listOf(image, protected, marker, outside), emptyList(), intent, destination)
        val moves = generated.plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves).hasSize(1)
        assertThat(moves.single().source).isEqualTo(FileRef.Direct(image.stableRef))
        assertThat(moves.single().destination).isEqualTo(FileRef.Direct("${destination.absolutePath}/Images/photo.jpg"))
        assertThat(generated.scopeReport.skippedByProtection).isAtLeast(1)
    }

    @Test
    fun nestedDestinationCreatesParentThenLeafThenMove() {
        val image = record("/storage/emulated/0/Download/photo.jpg", "photo.jpg", "jpg")
        val intent = BoundedIntent(
            action = IntentAction.ORGANIZE,
            rawRequest = "organize images into one main folder",
            categories = setOf(FileCategory.IMAGE),
            mainFolder = "Organized",
        )

        val generated = IntentPlanGenerator.generate(root, listOf(image), emptyList(), intent)
        val operations = generated.plan.operations

        assertThat(operations).hasSize(3)
        assertThat(operations[0]).isEqualTo(
            PlannedOperation.CreateDirectory(root, "Organized", "Destination for Extension \".jpg\" maps to image"),
        )
        assertThat(operations[1]).isEqualTo(
            PlannedOperation.CreateDirectory(
                FileRef.Direct("/storage/emulated/0/Download/Organized"),
                "Images",
                "Destination for Extension \".jpg\" maps to image",
            ),
        )
        assertThat(operations[2]).isEqualTo(
            PlannedOperation.Move(
                FileRef.Direct("/storage/emulated/0/Download/photo.jpg"),
                FileRef.Direct("/storage/emulated/0/Download/Organized/Images/photo.jpg"),
                "Extension \".jpg\" maps to image",
            ),
        )
    }

    @Test
    fun categoryFilterLeavesOtherKnownTypesUntouched() {
        val image = record("/storage/emulated/0/Download/photo.jpg", "photo.jpg", "jpg")
        val pdf = record("/storage/emulated/0/Download/report.pdf", "report.pdf", "pdf")
        val intent = BoundedIntent(
            action = IntentAction.ORGANIZE,
            rawRequest = "organize images",
            categories = setOf(FileCategory.IMAGE),
        )

        val generated = IntentPlanGenerator.generate(root, listOf(image, pdf), emptyList(), intent)
        val moves = generated.plan.operations.filterIsInstance<PlannedOperation.Move>()

        assertThat(moves).hasSize(1)
        assertThat(moves.single().source).isEqualTo(FileRef.Direct(image.stableRef))
    }

    private fun record(path: String, name: String, ext: String) = FileRecord(
        stableRef = path,
        displayName = name,
        extension = ext,
        mimeType = null,
        absolutePathOrUri = path,
        parentRef = root.absolutePath,
        sizeBytes = 10,
        createdAt = null,
        modifiedAt = null,
        lastScannedAt = 1,
        isDirectory = false,
        isHidden = false,
    )
}
