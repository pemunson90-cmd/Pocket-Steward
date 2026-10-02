package com.pocketsteward.app.projects

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.IndexedProjectLayout
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectDiscoveryStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private fun page(start: Int) = List(200) { n -> IndexedProjectLayout("/Projects/Project%05d".format(start + n), "Project${start + n}", false, "Notes") }
    @Test fun savesPagesBeyondFirstTwoHundredAndRestartsFromTheirStableCursor() {
        val store = ProjectDiscoveryStore(directory.root)
        store.append("scope+revision+roles", page(0)); store.append("scope+revision+roles", page(200))
        val restored = ProjectDiscoveryStore(directory.root).load("scope+revision+roles")
        assertThat(restored.rows).containsExactlyElementsIn(page(0) + page(200)).inOrder()
        assertThat(restored.complete).isFalse()
        store.append("scope+revision+roles", emptyList())
        assertThat(store.load("scope+revision+roles").complete).isTrue()
        assertThat(store.load("scope+newRevision+roles").rows).isEmpty()
    }
    @Test fun corruptTailDoesNotPoisonEarlierPagesAndCanBeReplacedOnResume() {
        val store = ProjectDiscoveryStore(directory.root)
        store.append("key", page(0)); store.append("key", page(200))
        val file = directory.root.listFiles()!!.single()
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 10) }
        assertThat(store.load("key").rows).containsExactlyElementsIn(page(0)).inOrder()
        store.append("key", page(200))
        assertThat(store.load("key").rows.size).isEqualTo(400)
    }
}
