package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryPolicyTest {
    @Test
    fun adoptsFoldersInsideTheLibraryRootOnly() {
        val root = "/storage/emulated/0"
        assertThat(LibraryPolicy.canAdopt(root, "/storage/emulated/0")).isTrue()
        assertThat(LibraryPolicy.canAdopt(root, "/storage/emulated/0/Download")).isTrue()
        assertThat(LibraryPolicy.canAdopt("$root/", "/storage/emulated/0/Download/")).isTrue()
        assertThat(LibraryPolicy.canAdopt("/storage/emulated/0/Down", "/storage/emulated/0/Download")).isFalse()
        assertThat(LibraryPolicy.canAdopt(root, "/storage/1234-5678/DCIM")).isFalse()
    }

    @Test
    fun grantedFolderUrisOnlyMatchThemselves() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs"
        assertThat(LibraryPolicy.canAdopt(tree, tree)).isTrue()
        assertThat(LibraryPolicy.canAdopt(tree, "${tree}%2FSub")).isFalse()
    }

    @Test
    fun freshnessWindow() {
        val now = 10_000_000L
        assertThat(LibraryPolicy.isFresh(null, now, 1000)).isFalse()
        assertThat(LibraryPolicy.isFresh(now - 500, now, 1000)).isTrue()
        assertThat(LibraryPolicy.isFresh(now - 1500, now, 1000)).isFalse()
        // A clock that went backwards is not "fresh forever".
        assertThat(LibraryPolicy.isFresh(now + 5000, now, 1000)).isFalse()
    }
}
