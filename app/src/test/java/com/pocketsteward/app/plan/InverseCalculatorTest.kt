package com.pocketsteward.app.plan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.MutationOperationType
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class InverseCalculatorTest {

    @Test
    fun `move inverse swaps destination and source`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.MOVE,
            sourceBefore = "/sd/Download/foo.apk",
            destinationAfter = "/sd/Download/APKs/foo.apk",
            undoState = null,
        )

        assertThat(action).isEqualTo(
            InverseAction.MoveBack(
                from = FileRef.Direct("/sd/Download/APKs/foo.apk"),
                to = FileRef.Direct("/sd/Download/foo.apk"),
            ),
        )
    }

    @Test
    fun `rename inverse is also a move-back, not a distinct case`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.RENAME,
            sourceBefore = "/sd/Download/report.txt",
            destinationAfter = "/sd/Download/2026-report.txt",
            undoState = null,
        )

        assertThat(action).isEqualTo(
            InverseAction.MoveBack(
                from = FileRef.Direct("/sd/Download/2026-report.txt"),
                to = FileRef.Direct("/sd/Download/report.txt"),
            ),
        )
    }

    @Test
    fun `trash inverse moves back out of the mirrored trash path`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.TRASH,
            sourceBefore = "/sd/Download/junk.apk",
            destinationAfter = "/sd/PocketSteward/Trash/Download/junk.apk",
            undoState = null,
        )

        assertThat(action).isEqualTo(
            InverseAction.MoveBack(
                from = FileRef.Direct("/sd/PocketSteward/Trash/Download/junk.apk"),
                to = FileRef.Direct("/sd/Download/junk.apk"),
            ),
        )
    }

    @Test
    fun `create directory that actually created something undoes as a removal`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.CREATE_DIRECTORY,
            sourceBefore = "/sd/Download",
            destinationAfter = "/sd/Download/APKs",
            undoState = InverseCalculator.CREATED_MARKER,
        )

        assertThat(action).isEqualTo(InverseAction.RemoveDirectoryIfEmpty(FileRef.Direct("/sd/Download/APKs")))
    }

    @Test
    fun `create directory that found an existing folder has nothing to undo`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.CREATE_DIRECTORY,
            sourceBefore = "/sd/Download",
            destinationAfter = "/sd/Download/APKs",
            undoState = null,
        )

        assertThat(action).isEqualTo(InverseAction.NothingToUndo)
    }

    @Test
    fun `a move with no recorded destination has nothing to undo rather than crashing`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.MOVE,
            sourceBefore = "/sd/Download/foo.apk",
            destinationAfter = null,
            undoState = null,
        )

        assertThat(action).isEqualTo(InverseAction.NothingToUndo)
    }

    @Test
    fun `copy has no inverse defined yet`() {
        val action = InverseCalculator.compute(
            operationType = MutationOperationType.COPY,
            sourceBefore = "/sd/Download/foo.apk",
            destinationAfter = "/sd/Download/APKs/foo.apk",
            undoState = null,
        )

        assertThat(action).isEqualTo(InverseAction.NothingToUndo)
    }
}
