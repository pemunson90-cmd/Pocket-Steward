package com.pocketsteward.app.library

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryInvalidationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun pendingRefreshSurvivesRestartAndCompletionClearsIt() {
        val file = temporary.newFolder().resolve("pending")
        DirectoryInvalidationStore(file).mark(setOf("/storage/0/Download", "/storage/0/Documents/Lilith/Notes"))
        val store = DirectoryInvalidationStore(file)
        val snapshot = store.claim()
        assertThat(snapshot.directories.keys).containsExactly("/storage/0/Download", "/storage/0/Documents/Lilith/Notes")
        assertThat(store.complete(snapshot)).isTrue()
        assertThat(DirectoryInvalidationStore(file).claim().pending).isFalse()
    }
    @Test fun mutationDuringAClaimedScanRemainsPendingAfterItsOlderSnapshotCompletes() {
        val store = DirectoryInvalidationStore(temporary.newFolder().resolve("pending"))
        store.mark(setOf("/storage/0/Download"))
        val before = store.claim()
        store.mark(setOf("/storage/0/Download", "/storage/0/Documents/Lilith"))
        store.complete(before)
        val after = store.claim()
        assertThat(after.directories.keys).containsExactly("/storage/0/Download", "/storage/0/Documents/Lilith")
        assertThat(after.generation).isGreaterThan(before.generation)
    }
    @Test fun repeatedOperationsWithinOneUnclaimedTaskDoNotRewriteTheSameHint() {
        val store = DirectoryInvalidationStore(temporary.newFolder().resolve("pending"))
        repeat(16_000) { store.mark(setOf("/storage/0/Download", "/storage/0/Documents/Lilith/Notes")) }
        assertThat(store.claim().generation).isEqualTo(1)
    }
    @Test fun failedScanDoesNotDiscardDirtyDirectories() {
        val file = temporary.newFolder().resolve("pending")
        val store = DirectoryInvalidationStore(file)
        store.mark(setOf("/storage/0/Download"))
        store.release(store.claim())
        assertThat(DirectoryInvalidationStore(file).claim().pending).isTrue()
    }
    @Test fun anotherFullInvalidationDuringScanCannotBeClearedByTheOldCompletion() {
        val store = DirectoryInvalidationStore(temporary.newFolder().resolve("pending"))
        store.markFull()
        val before = store.claim()
        store.markFull()
        assertThat(store.complete(before)).isTrue()
        assertThat(store.hasPending()).isTrue()
        val after = store.claim()
        assertThat(after.fullGeneration).isGreaterThan(before.fullGeneration!!)
        store.complete(after)
        assertThat(store.hasPending()).isFalse()
    }
    @Test fun oversizedHintSetsBecomeOneFullReconciliationHint() {
        val store = DirectoryInvalidationStore(temporary.newFolder().resolve("pending"))
        store.mark((0 until 1_000).mapTo(hashSetOf()) { "/storage/0/Project$it" })
        val snapshot = store.claim()
        assertThat(snapshot.directories).isEmpty()
        assertThat(snapshot.fullGeneration).isEqualTo(1)
        assertThat(snapshot.pending).isTrue()
    }
    @Test fun corruptPrivateHintsRequestReconciliationWithoutInventingDirectories() {
        val file = temporary.newFile()
        file.writeText("interrupted")
        val snapshot = DirectoryInvalidationStore(file).claim()
        assertThat(snapshot.fullGeneration).isNotNull()
        assertThat(snapshot.directories).isEmpty()
    }
    @Test fun unwritableStorageLeavesPriorInMemoryHintsUntouched() {
        val parent = temporary.newFile()
        val store = DirectoryInvalidationStore(parent.resolve("pending"))
        assertThat(store.mark(setOf("/storage/0/Download"))).isFalse()
        assertThat(store.claim().pending).isFalse()
    }
}
