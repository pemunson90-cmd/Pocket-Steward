package com.pocketsteward.app.evidence

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.storage.*
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ObservedEvidenceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun path() = File(temporary.newFolder(), "revisions")

    @Test fun writesInvalidateOnlyTheAffectedFileOrDirectorySegmentAndSurviveRestart() {
        val file = path(); val store = ObservedEvidenceStore(file)
        val original = store.revision("/Downloads/Project/a.txt")
        store.observe(setOf("/Downloads/Project/a.txt"))
        val changed = store.revision("/Downloads/Project/a.txt")
        assertThat(changed).isNotEqualTo(original)
        assertThat(store.revision("/Downloads/Project/b.txt")).isEqualTo(original)
        assertThat(ObservedEvidenceStore(file).revision("/Downloads/Project/a.txt")).isEqualTo(changed)
        store.observe(setOf("/Downloads/Project"))
        assertThat(store.revision("/Downloads/Project/b.txt")).isNotEqualTo(original)
        assertThat(store.revision("/Downloads/Project2/a.txt")).isEqualTo(original)
    }

    @Test fun burstIsCoalescedUntilAReaderObservesItThenTheNextWriteGetsANewRevision() {
        val store = ObservedEvidenceStore(path()); store.inventoryRevision()
        repeat(16_000) { store.observe(setOf("/Downloads/a.txt")) }
        assertThat(store.inventoryRevision().substringAfterLast(':')).isEqualTo("1")
        store.observe(setOf("/Downloads/a.txt"))
        assertThat(store.inventoryRevision().substringAfterLast(':')).isEqualTo("2")
    }

    @Test fun unflushedEventFenceAndCorruptStateInvalidateOldEvidenceAfterProcessLoss() {
        val file = path(); val store = ObservedEvidenceStore(file)
        val a = store.revision("/Downloads/a.txt"); val b = store.revision("/Downloads/b.txt")
        store.observe(setOf("/Downloads/a.txt")) // Process dies before the coalesced save.
        val restarted = ObservedEvidenceStore(file)
        assertThat(restarted.revision("/Downloads/a.txt")).isNotEqualTo(a)
        assertThat(restarted.revision("/Downloads/b.txt")).isNotEqualTo(b)
        val current = restarted.revision("/Downloads/a.txt")
        file.writeText("damaged private state")
        assertThat(ObservedEvidenceStore(file).revision("/Downloads/a.txt")).isNotEqualTo(current)
    }

    @Test fun sixteenThousandDistinctEventsHaveBoundedStateAndOpaqueUrisNeverUsePrefixes() {
        val file = path(); val store = ObservedEvidenceStore(file)
        val before = store.revision("content://provider/document/opaque")
        store.observe(setOf("content://provider/document/opaque"))
        assertThat(store.revision("content://provider/document/opaque/pretender")).isEqualTo(before)
        repeat(16_000) { store.observe(setOf("/Downloads/$it.txt")) }
        assertThat(store.revision("/Elsewhere/a.txt")).isNotEqualTo(before)
        assertThat(file.length()).isLessThan(1_048_576)
        assertThat(ObservedEvidenceStore(file).inventoryRevision()).isEqualTo(store.inventoryRevision())
    }

    @Test fun unwritablePrivateStateCannotClaimCacheFreshness() {
        val parent = temporary.newFile()
        val store = ObservedEvidenceStore(File(parent, "revisions"))
        assertThat(store.observe(setOf("/Downloads/a.txt"))).isFalse()
        assertThat(runCatching { store.revision("/Downloads/a.txt") }.isFailure).isTrue()
    }

    @Test fun observedUnsampledMiddleWritesChangeCacheStampButUnrelatedFilesStayReusable() = runTest {
        val file = temporary.newFile().apply { writeBytes(ByteArray(100_000) { 65 }) }
        val store = ObservedEvidenceStore(path())
        val gateway = Proxy.newProxyInstance(StorageGateway::class.java.classLoader, arrayOf(StorageGateway::class.java)) { _, method, _ ->
            when (method.name) { "openRead" -> file.inputStream(); else -> error(method.name) }
        } as StorageGateway
        val inspector = ContentInspector(gateway, observedRevision = store::revision)
        val before = inspector.evidenceFingerprint(file.path, file.length())
        val unrelated = store.revision("/Downloads/unrelated.txt")
        file.writeBytes(file.readBytes().apply { this[50_000] = 66 })
        assertThat(inspector.evidenceFingerprint(file.path, file.length())).isEqualTo(before) // Sampling alone cannot see the middle.
        store.observe(setOf(file.path))
        assertThat(inspector.evidenceFingerprint(file.path, file.length())).isNotEqualTo(before)
        assertThat(store.revision("/Downloads/unrelated.txt")).isEqualTo(unrelated)
    }

    @Test fun aWriteDuringTheByteSampleCannotReturnVerifiedEvidence() = runTest {
        val store = ObservedEvidenceStore(path())
        val gateway = Proxy.newProxyInstance(StorageGateway::class.java.classLoader, arrayOf(StorageGateway::class.java)) { _, method, _ ->
            when (method.name) {
                "openRead" -> { store.observe(setOf("/Downloads/a.txt")); byteArrayOf(65).inputStream() }
                else -> error(method.name)
            }
        } as StorageGateway
        val inspector = ContentInspector(gateway, observedRevision = store::revision)
        assertThat(runCatching { inspector.evidenceFingerprint("/Downloads/a.txt", 1) }.isFailure).isTrue()
    }
}
