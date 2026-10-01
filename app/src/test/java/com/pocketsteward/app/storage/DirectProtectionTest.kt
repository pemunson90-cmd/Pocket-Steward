package com.pocketsteward.app.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DirectProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun freshlyAddedAncestorMarkerIsDetectedBeforeNextMutation() {
        val root = temporary.newFolder("storage")
        val parent = File(root, "Downloads").apply { mkdir() }
        val source = File(parent, "notes.txt").apply { writeText("notes") }
        assertThat(DirectProtection.refusal(root.path, source.path)).isNull()
        File(parent, DirectProtection.MARKER).writeText("protected")
        assertThat(DirectProtection.refusal(root.path, source.path)).contains("Protected")
        assertThat(DirectProtection.refusalDestination(root.path, File(parent, "new-file.txt").path)).contains("Protected")
        assertThat(DirectProtection.refusalDestination(root.path, File(parent, "missing/file.txt").path)).isNotNull()
    }
    @Test fun markerDirectoryAndDanglingMarkerSymlinkRemainConservativeProtection() {
        val root = temporary.newFolder("storage")
        val parent = File(root, "Downloads").apply { mkdir() }
        val source = File(parent, "notes.txt").apply { writeText("notes") }
        val marker = File(parent, DirectProtection.MARKER)
        marker.mkdir()
        assertThat(DirectProtection.refusal(root.path, source.path)).contains("Protected")
        marker.delete()
        Files.createSymbolicLink(marker.toPath(), File(parent, "missing").toPath())
        assertThat(DirectProtection.refusal(root.path, source.path)).contains("Protected")
    }
    @Test fun outsideRootAndMissingAncestryNeverGrantPermission() {
        val root = temporary.newFolder("storage")
        val outside = temporary.newFile("outside.txt")
        assertThat(DirectProtection.refusal(root.path, outside.path)).isNotNull()
        assertThat(DirectProtection.refusal(root.path, File(root, "missing/file.txt").path)).isNotNull()
    }
}
