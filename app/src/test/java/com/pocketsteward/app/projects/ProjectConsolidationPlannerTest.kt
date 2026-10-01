package com.pocketsteward.app.projects

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.DirectProtection
import org.junit.Test

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
    private fun row(path: String, directory: Boolean = false) = FileRecord(stableRef = path, displayName = path.substringAfterLast('/'), extension = path.substringAfterLast('.', ""), mimeType = null, absolutePathOrUri = path, parentRef = path.substringBeforeLast('/'), sizeBytes = 10, createdAt = null, modifiedAt = 1, lastScannedAt = 1, isDirectory = directory, isHidden = false)
}
