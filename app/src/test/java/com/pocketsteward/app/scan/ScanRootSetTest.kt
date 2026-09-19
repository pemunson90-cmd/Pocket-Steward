package com.pocketsteward.app.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanRootSetTest {
    @Test
    fun parentSelectionDropsDescendant() {
        assertThat(
            ScanRootSet.normalize(
                listOf(
                    "/storage/emulated/0/Download/Receipts",
                    "/storage/emulated/0/Download",
                ),
            ),
        ).containsExactly("/storage/emulated/0/Download")
    }

    @Test
    fun siblingRootsBothRemain() {
        assertThat(
            ScanRootSet.normalize(
                listOf(
                    "/storage/emulated/0/Download",
                    "/storage/emulated/0/Pictures",
                ),
            ),
        ).containsExactly(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Pictures",
        )
    }

    @Test
    fun duplicateRootsCollapse() {
        assertThat(
            ScanRootSet.normalize(
                listOf(
                    "/storage/emulated/0/Download/",
                    "/storage/emulated/0/Download",
                ),
            ),
        ).containsExactly("/storage/emulated/0/Download")
    }

    @Test
    fun lexicalPrefixIsNotTreatedAsDescendant() {
        assertThat(
            ScanRootSet.normalize(
                listOf(
                    "/storage/emulated/0/Download",
                    "/storage/emulated/0/Downloads2",
                ),
            ),
        ).containsExactly(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads2",
        )
    }

    @Test
    fun inputOrderDoesNotChangeResult() {
        val first = ScanRootSet.normalize(
            listOf(
                "/storage/emulated/0/Download/Receipts",
                "/storage/emulated/0/Pictures",
                "/storage/emulated/0/Download",
            ),
        )
        val second = ScanRootSet.normalize(
            listOf(
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Pictures",
                "/storage/emulated/0/Download/Receipts",
            ),
        )
        assertThat(first).containsExactlyElementsIn(second).inOrder()
    }

    @Test
    fun rootPathContainsEveryOtherAbsolutePath() {
        assertThat(
            ScanRootSet.normalize(
                listOf("/", "/storage/emulated/0/Download"),
            ),
        ).containsExactly("/")
    }
}
