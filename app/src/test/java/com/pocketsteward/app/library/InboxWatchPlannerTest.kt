package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InboxWatchPlannerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun nestedFoldersAreWatchedAndCheckpointsAndConfiguredRootsComeFirst() {
        val storage = temporary.newFolder(); val downloads = File(storage, "Downloads").apply { mkdirs() }
        val other = File(storage, "Other").apply { mkdirs() }
        val checkpoint = File(downloads, "uncertain").apply { mkdirs() }
        val nested = File(downloads, "bundle/notes").apply { mkdirs() }
        val result = InboxWatchPlanner.plan(storage.path, listOf(downloads.path, other.path))
        assertThat(result.paths.take(3)).containsExactly(downloads.path, other.path, checkpoint.path).inOrder()
        assertThat(result.paths).contains(nested.path)
        assertThat(result.limited).isFalse()
    }
    @Test fun folderAndEntryBudgetsAndSymlinksKeepEnumerationBounded() {
        val storage = temporary.newFolder(); val downloads = File(storage, "Downloads").apply { mkdirs() }
        repeat(100) { File(downloads, "folder$it").mkdirs() }
        val outside = temporary.newFolder(); File(outside, "private").mkdirs()
        Files.createSymbolicLink(File(downloads, "link").toPath(), outside.toPath())
        val result = InboxWatchPlanner.plan(storage.path, listOf(downloads.path), maxFolders = 10)
        assertThat(result.paths).hasSize(10); assertThat(result.limited).isTrue()
        assertThat(result.paths).doesNotContain(outside.path)
        val entryLimit = InboxWatchPlanner.plan(storage.path, listOf(downloads.path), maxEntries = 5)
        assertThat(entryLimit.limited).isTrue()
        assertThat(entryLimit.paths.size).isAtMost(6)
    }
    @Test fun excludedAndOutsideRootsAreNotEnumerated() {
        val storage = temporary.newFolder(); val downloads = File(storage, "Downloads").apply { mkdirs() }
        val excluded = File(downloads, "excluded").apply { mkdirs() }
        val hidden = File(excluded, "deep").apply { mkdirs() }
        val result = InboxWatchPlanner.plan(storage.path, listOf(downloads.path, excluded.path, temporary.newFolder().path),
            excluded = { it == excluded.path || it.startsWith("${excluded.path}/") })
        assertThat(result.paths).containsExactly(downloads.path)
        assertThat(result.paths).doesNotContain(hidden.path)
    }
}
