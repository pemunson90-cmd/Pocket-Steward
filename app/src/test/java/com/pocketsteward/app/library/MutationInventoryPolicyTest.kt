package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class MutationInventoryPolicyTest {
    @Test fun movesKeepBothSourceAndDestinationInventoriesCurrent() {
        val operations = listOf(PlannedOperation.Move(FileRef.Direct("/storage/0/Download/a.txt"), FileRef.Direct("/storage/0/Documents/Lilith/Notes/a.txt"), "Approved"))
        assertThat(MutationInventoryPolicy.directories(operations)).containsExactly("/storage/0/Download", "/storage/0/Documents/Lilith/Notes")
    }
    @Test fun copiesDoNotInventAnOpaqueProviderParentOrInvalidateTheirUnchangedSource() {
        val operations = listOf(PlannedOperation.Copy(FileRef.Saf("content://provider/document/case%3Aone"), FileRef.Direct("/storage/0/Documents/a.txt"), "Approved copy"))
        assertThat(MutationInventoryPolicy.directories(operations)).containsExactly("/storage/0/Documents")
    }
    @Test fun folderCreationAndTextWritesInvalidateTheirExistingParent() {
        val operations = listOf(PlannedOperation.CreateDirectory(FileRef.Direct("/storage/0/Documents"), "Lilith", "Approved"),
            PlannedOperation.WriteTextFile(FileRef.Direct("/storage/0/Documents/Lilith"), "a.txt", "Text", "Approved"))
        assertThat(MutationInventoryPolicy.directories(operations)).containsExactly("/storage/0/Documents", "/storage/0/Documents/Lilith")
    }
    @Test fun mirroredTrashInventoryIsRefreshedAlongsideTheOriginalArea() {
        val source = FileRef.Direct("/storage/0/Download/Bundle")
        val trash = FileRef.Direct("/storage/0/PocketSteward/Trash/Download/Bundle")
        assertThat(MutationInventoryPolicy.directories(listOf(PlannedOperation.Trash(source, "Approved")), listOf(trash)))
            .containsExactly("/storage/0/Download", "/storage/0/PocketSteward/Trash/Download")
    }
    @Test fun sixteenThousandMovesProduceOnlyTheirDistinctDirectories() {
        val operations = List(16_000) { n -> PlannedOperation.Move(FileRef.Direct("/storage/0/Download/$n.txt"), FileRef.Direct("/storage/0/Documents/Lilith/Notes/$n.txt"), "Approved") }
        assertThat(MutationInventoryPolicy.directories(operations)).hasSize(2)
    }
    @Test fun evidenceInvalidationPreservesUnchangedCopySourcesAndOpaqueBoundaries() {
        val source = FileRef.Direct("/storage/0/Download/a.txt")
        val destination = FileRef.Direct("/storage/0/Documents/a.txt")
        val copied = MutationInventoryPolicy.evidenceChanges(listOf(PlannedOperation.Copy(source, destination, "Approved")))
        assertThat(copied.refs).containsExactly(destination.absolutePath)
        assertThat(copied.full).isFalse()
        val renamed = MutationInventoryPolicy.evidenceChanges(listOf(PlannedOperation.Rename(source, "b.txt", "Approved")))
        assertThat(renamed.refs).containsExactly(source.absolutePath, "/storage/0/Download/b.txt")
        val opaque = MutationInventoryPolicy.evidenceChanges(listOf(PlannedOperation.Move(FileRef.Saf("content://provider/document/opaque"), destination, "Approved")))
        assertThat(opaque.full).isTrue()
        assertThat(opaque.refs).containsExactly(destination.absolutePath)
    }
}
