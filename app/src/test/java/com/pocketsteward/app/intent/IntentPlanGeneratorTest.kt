package com.pocketsteward.app.intent

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class IntentPlanGeneratorTest {
    private val root = FileRef.Direct("/storage/emulated/0/Download")

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
