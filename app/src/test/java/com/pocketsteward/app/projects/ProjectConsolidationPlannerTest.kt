package com.pocketsteward.app.projects

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.DirectProtection
import org.junit.Test
import org.junit.Assert.assertThrows
import java.util.concurrent.CancellationException

class ProjectConsolidationPlannerTest {
    private val source = FileRef.Direct("/Documents/Lilith-old")
    private val target = FileRef.Direct("/Documents/Lilith")
    @Test fun unmatchedBundleMovesIntactAndMatchingRoleFoldersMergeWithoutOverwrite() {
        val sources = listOf(row("${source.absolutePath}/Release", true), row("${source.absolutePath}/Release/cover.png"), row("${source.absolutePath}/Notes", true), row("${source.absolutePath}/Notes/new.txt"), row("${source.absolutePath}/Notes/SAME.txt"))
        val destinations = listOf(row("${target.absolutePath}/Notes", true), row("${target.absolutePath}/Notes/same.TXT"))
        val plan = ProjectConsolidationPlanner.build(source, target, sources, destinations)
        assertThat(plan.moves.map { it.source }).containsExactly(FileRef.Direct("${source.absolutePath}/Release"), FileRef.Direct("${source.absolutePath}/Notes/new.txt"))
        assertThat(plan.held.single().displayName).isEqualTo("SAME.txt")
    }
    @Test fun folderContainingProtectedSubtreeStaysTogether() {
        val sources = listOf(row("${source.absolutePath}/Release", true), row("${source.absolutePath}/Release/${DirectProtection.MARKER}"), row("${source.absolutePath}/Release/cover.png"))
        val plan = ProjectConsolidationPlanner.build(source, target, sources, emptyList())
        assertThat(plan.moves).isEmpty()
        assertThat(plan.held.single().displayName).isEqualTo("Release")
    }
    @Test(expected = IllegalArgumentException::class) fun nestedHomesCannotMergeRecursively() {
        ProjectConsolidationPlanner.build(source, FileRef.Direct("${source.absolutePath}/Nested"), emptyList(), emptyList())
    }
    @Test fun opaqueHomesMergeMatchingRolesAndMoveUnmatchedBundlesIntact() {
        val from = saf("source"); val to = saf("target")
        val sources = listOf(opaque("s-notes", "source", "Notes", true), opaque("s-new", "s-notes", "new.txt"),
            opaque("s-same", "s-notes", "same.TXT"), opaque("s-release", "source", "Release", true),
            opaque("s-content", "s-release", "build.apk"))
        val targets = listOf(opaque("t-notes", "target", "notes", true), opaque("t-same", "t-notes", "SAME.txt"))
        val plan = ProjectConsolidationPlanner.build(from, to, sources, targets)
        assertThat(plan.moves.map { it.source }).containsExactly(saf("s-new"), saf("s-release"))
        assertThat(plan.moves.first { it.source == saf("s-new") }.destination).isEqualTo(FileRef.Child(saf("t-notes"), "new.txt"))
        assertThat(plan.moves.first { it.source == saf("s-release") }.destination).isEqualTo(FileRef.Child(to, "Release"))
        assertThat(plan.held.single().stableRef).isEqualTo(saf("s-same").documentUri)
        assertThat(plan.heldReasons.values.single()).contains("already contains")
    }
    @Test fun opaqueProtectionUsesParentLinksAndLeavesUnrelatedSiblingsAvailable() {
        val sources = listOf(opaque("protected", "source", "Release", true), opaque("marker", "protected", DirectProtection.MARKER),
            opaque("protected-looking-id", "source", "Other", true), opaque("safe-note", "protected-looking-id", "note.txt"))
        val plan = ProjectConsolidationPlanner.build(saf("source"), saf("target"), sources, emptyList())
        assertThat(plan.held.single().stableRef).isEqualTo(saf("protected").documentUri)
        assertThat(plan.moves.single().source).isEqualTo(saf("protected-looking-id"))
    }
    @Test fun ambiguousSourceOrDestinationCaseNamesNeverChooseAFolderByListOrder() {
        val sources = listOf(opaque("notes", "source", "Notes", true), opaque("draft-a", "source", "Drafts", true), opaque("draft-b", "source", "drafts", true))
        val targets = listOf(opaque("notes-a", "target", "Notes", true), opaque("notes-b", "target", "notes", true))
        val plan = ProjectConsolidationPlanner.build(saf("source"), saf("target"), sources, targets)
        assertThat(plan.moves).isEmpty()
        assertThat(plan.held).hasSize(3)
        assertThat(plan.heldReasons.values.all { "explicit choice" in it }).isTrue()
    }
    @Test fun opaqueNestedAndDisconnectedInventoriesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectConsolidationPlanner.build(saf("source"), saf("target"), listOf(opaque("target", "source", "Nested", true)), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectConsolidationPlanner.build(saf("source"), saf("target"), listOf(opaque("lost", "elsewhere", "lost.txt")), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectConsolidationPlanner.build(saf("source"), saf("target"), listOf(opaque("cycle", "cycle", "Cycle", true)), emptyList())
        }
    }
    @Test fun destinationNoSortMarkerHoldsSourcesWithoutMoves() {
        val plan = ProjectConsolidationPlanner.build(saf("source"), saf("target"), listOf(opaque("file", "source", "scene.txt")),
            listOf(opaque("marker", "target", DirectProtection.MARKER)))
        assertThat(plan.moves).isEmpty()
        assertThat(plan.heldReasons.values.single()).contains("no-sort marker")
    }
    @Test fun cancellationDuringSixteenThousandFileConsolidationPropagates() {
        val sources = (1..16_000).map { opaque("file-$it", "source", "scene-$it.txt") }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            ProjectConsolidationPlanner.build(saf("source"), saf("target"), sources, emptyList()) {
                if (++checks == 50) throw CancellationException("Cancelled consolidation")
            }
        }
        assertThat(checks).isEqualTo(50)
    }
    private fun saf(id: String) = FileRef.Saf("content://opaque.projects/tree/root/document/$id")
    private fun opaque(id: String, parent: String, name: String, directory: Boolean = false) = FileRecord(
        stableRef = saf(id).documentUri, displayName = name, extension = name.substringAfterLast('.', ""),
        mimeType = null, absolutePathOrUri = saf(id).documentUri, parentRef = saf(parent).documentUri,
        sizeBytes = 10, createdAt = null, modifiedAt = 1, lastScannedAt = 1, isDirectory = directory, isHidden = false,
    )
    private fun row(path: String, directory: Boolean = false) = FileRecord(stableRef = path, displayName = path.substringAfterLast('/'), extension = path.substringAfterLast('.', ""), mimeType = null, absolutePathOrUri = path, parentRef = path.substringBeforeLast('/'), sizeBytes = 10, createdAt = null, modifiedAt = 1, lastScannedAt = 1, isDirectory = directory, isHidden = false)
}
