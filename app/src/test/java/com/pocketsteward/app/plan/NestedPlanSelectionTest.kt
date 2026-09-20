package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class NestedPlanSelectionTest {
    private val root = FileRef.Direct("/sd/Download")
    private val parent = PlannedOperation.CreateDirectory(root, "Organized", "parent")
    private val child = PlannedOperation.CreateDirectory(
        FileRef.Direct("/sd/Download/Organized"),
        "Images",
        "child",
    )
    private val move = PlannedOperation.Move(
        FileRef.Direct("/sd/Download/photo.jpg"),
        FileRef.Direct("/sd/Download/Organized/Images/photo.jpg"),
        "image",
    )

    @Test
    fun selectingNestedMoveSelectsEveryRequiredDirectory() {
        val operations = listOf(parent, child, move)

        val selected = PlanSelection.setSelected(operations, emptySet(), 2, true)

        assertThat(selected).containsExactly(0, 1, 2)
    }

    @Test
    fun deselectingParentDropsChildAndMove() {
        val operations = listOf(parent, child, move)

        val selected = PlanSelection.setSelected(
            operations,
            PlanSelection.allSelected(operations),
            0,
            false,
        )

        assertThat(selected).isEmpty()
    }

    @Test
    fun deselectingMovePrunesNowUnusedNestedTree() {
        val operations = listOf(parent, child, move)

        val selected = PlanSelection.setSelected(
            operations,
            PlanSelection.allSelected(operations),
            2,
            false,
        )

        assertThat(selected).isEmpty()
    }
}
