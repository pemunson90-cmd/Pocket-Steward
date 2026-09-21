package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class PlanSelectionTest {
    private val root = FileRef.Direct("/storage/emulated/0/Download")
    private val destinationFolder = PlannedOperation.CreateDirectory(
        parent = root,
        name = "Images",
        reason = "destination",
    )
    private val moveA = PlannedOperation.Move(
        source = FileRef.Direct("/storage/emulated/0/Download/a.jpg"),
        destination = FileRef.Direct("/storage/emulated/0/Download/Images/a.jpg"),
        reason = "image",
    )
    private val moveB = PlannedOperation.Move(
        source = FileRef.Direct("/storage/emulated/0/Download/b.jpg"),
        destination = FileRef.Direct("/storage/emulated/0/Download/Images/b.jpg"),
        reason = "image",
    )

    @Test
    fun allSelected_selectsEveryAcceptedOperation() {
        val operations = listOf(destinationFolder, moveA, moveB)
        assertThat(PlanSelection.allSelected(operations)).containsExactly(0, 1, 2)
    }

    @Test
    fun deselectingCreateDirectory_deselectsDependentMoves() {
        val operations = listOf(destinationFolder, moveA, moveB)
        val selected = PlanSelection.setSelected(
            operations,
            PlanSelection.allSelected(operations),
            index = 0,
            selected = false,
        )
        assertThat(selected).isEmpty()
    }

    @Test
    fun deselectingOneOfTwoMoves_keepsRequiredDirectory() {
        val operations = listOf(destinationFolder, moveA, moveB)
        val selected = PlanSelection.setSelected(
            operations,
            PlanSelection.allSelected(operations),
            index = 1,
            selected = false,
        )
        assertThat(selected).containsExactly(0, 2)
    }

    @Test
    fun deselectingLastMove_deselectsNowUnusedDirectory() {
        val operations = listOf(destinationFolder, moveA)
        val selected = PlanSelection.setSelected(
            operations,
            PlanSelection.allSelected(operations),
            index = 1,
            selected = false,
        )
        assertThat(selected).isEmpty()
    }

    @Test
    fun selectingMove_reselectsRequiredDirectory() {
        val operations = listOf(destinationFolder, moveA)
        val selected = PlanSelection.setSelected(
            operations,
            emptySet(),
            index = 1,
            selected = true,
        )
        assertThat(selected).containsExactly(0, 1)
    }

    @Test
    fun standaloneDirectory_canBeSelectedWithoutMoves() {
        val operations = listOf(destinationFolder)
        val selected = PlanSelection.setSelected(
            operations,
            emptySet(),
            index = 0,
            selected = true,
        )
        assertThat(selected).containsExactly(0)
    }
    @Test
    fun safeSelected_excludesRedTrashButKeepsSafeDependencies() {
        val trash = PlannedOperation.Trash(
            source = FileRef.Direct("/storage/emulated/0/Download/duplicate.jpg"),
            reason = "duplicate",
        )
        val operations = listOf(destinationFolder, moveA, trash)

        val selected = PlanSelection.safeSelected(operations)

        assertThat(selected).containsExactly(0, 1)
    }

    @Test
    fun noneSelected_isEmpty() {
        assertThat(PlanSelection.noneSelected()).isEmpty()
    }

}
