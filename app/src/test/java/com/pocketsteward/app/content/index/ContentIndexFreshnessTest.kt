package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import org.junit.Test

class ContentIndexFreshnessTest {
    private class Fixture {
        var bytes = "Project: Lilith".toByteArray()
        val record = FileRecord(stableRef = "/Downloads/a.txt", displayName = "a.txt", extension = "txt", mimeType = "text/plain",
            absolutePathOrUri = "/Downloads/a.txt", parentRef = "/Downloads", sizeBytes = bytes.size.toLong(),
            createdAt = null, modifiedAt = 100, lastScannedAt = 100, isDirectory = false, isHidden = false)
        var live = FileMetadata(FileRef.Direct(record.stableRef), record.displayName, "txt", "text/plain", record.sizeBytes, null, 100, false, false)
        var revoke = false
        var reads = 0
        var allowed = true
        var afterRead: () -> Unit = {}
        var stored: IndexedDocument? = null
        var segments = emptyList<IndexedSegment>()
        val gateway = proxy<StorageGateway> { name, _ ->
            when (name) {
                "stat" -> { if (revoke) throw SecurityException("Access revoked"); live }
                "openRead" -> { reads++; bytes.inputStream().also { afterRead() } }
                else -> error(name)
            }
        }
        val dao = proxy<ContentIndexDao> { name, args ->
            when (name) {
                "getDocument" -> stored
                "putScope" -> Unit
                "replaceDocument" -> {
                    stored = args[0] as IndexedDocument
                    @Suppress("UNCHECKED_CAST")
                    segments = args[1] as List<IndexedSegment>
                    Unit
                }
                else -> error(name)
            }
        }
        val repository = ContentIndexRepository(dao, ContentInspector(gateway), inspectionAllowed = { allowed })
        suspend fun inspect() = repository.ensureDocument(ContentIndexCandidate(record, "/Downloads"))
    }
    @Test fun unchangedDocumentUsesCacheAfterVerifyingSource() = runTest {
        val f = Fixture()
        f.inspect()
        assertThat(f.inspect().reused).isTrue()
        assertThat(f.reads).isEqualTo(4)
    }
    @Test fun staleInventoryCannotServeOrReextractChangedSourceWithOldIdentity() = runTest {
        val f = Fixture()
        f.inspect()
        f.live = f.live.copy(modifiedAtEpochMs = 101)
        val result = f.inspect()
        assertThat(result.reused).isFalse()
        assertThat(result.document.extractionStatus).isEqualTo("FAILED")
        assertThat(result.document.extractionError).contains("changed")
        assertThat(f.segments).isEmpty()
        assertThat(f.reads).isEqualTo(3)
    }
    @Test fun revokedPermissionInvalidatesOldTextInsteadOfReturningTheCache() = runTest {
        val f = Fixture()
        f.inspect()
        f.revoke = true
        assertThat(f.inspect().document.extractionStatus).isEqualTo("FAILED")
        assertThat(f.segments).isEmpty()
    }
    @Test fun changesDuringExtractionCannotBecomeCurrentEvidence() = runTest {
        val f = Fixture()
        f.afterRead = { f.live = f.live.copy(sizeBytes = 200) }
        assertThat(f.inspect().document.extractionStatus).isEqualTo("FAILED")
        assertThat(f.segments).isEmpty()
    }
    @Test fun permissionLossAfterReadCannotPublishExtractedText() = runTest {
        val f = Fixture()
        f.afterRead = { f.revoke = true }
        assertThat(f.inspect().document.extractionStatus).isEqualTo("FAILED")
        assertThat(f.segments).isEmpty()
    }
    @Test fun cancellationCannotStoreFailedEvidenceOrClearThePreviousCache() = runTest {
        val f = Fixture()
        f.inspect()
        val original = f.stored
        f.stored = original!!.copy(extractionStatus = "FAILED") // Force extraction.
        val before = f.stored
        val parent = Job()
        f.afterRead = { parent.cancel() }
        val job = launch(parent) { f.inspect(); error("Cancelled evidence escaped") }
        job.join()
        assertThat(f.stored).isEqualTo(before)
        assertThat(job.isCancelled).isTrue()
    }
    @Test fun sourceTypeChangesInvalidateCachedText() = runTest {
        val f = Fixture()
        f.inspect()
        f.live = f.live.copy(isDirectory = true)
        assertThat(f.inspect().document.extractionStatus).isEqualTo("FAILED")
        assertThat(f.segments).isEmpty()
    }
    @Test fun changedBytesWithSameSizeAndDateReplaceCachedProjectEvidence() = runTest {
        val f = Fixture()
        f.inspect()
        f.bytes = "Project: NSTL!!".toByteArray()
        assertThat(f.bytes.size.toLong()).isEqualTo(f.record.sizeBytes)
        val result = f.inspect()
        assertThat(result.reused).isFalse()
        assertThat(result.document.extractionStatus).isEqualTo("INDEXED")
        assertThat(f.segments.joinToString { it.body }).contains("NSTL")
        assertThat(f.segments.joinToString { it.body }).doesNotContain("Lilith")
        assertThat(f.record.quickFingerprint).isNull() // Duplicate fingerprints are never overwritten.
    }
    @Test fun byteChangeDuringExtractionWithUnchangedMetadataCannotPublishText() = runTest {
        val f = Fixture()
        f.afterRead = { if (f.reads == 2) f.bytes = "Project: NSTL!!".toByteArray() }
        assertThat(f.inspect().document.extractionStatus).isEqualTo("FAILED")
        assertThat(f.segments).isEmpty()
        assertThat(f.stored!!.extractionError).contains("bytes changed")
    }
    @Test fun disablingInspectionStopsByteReadsAndPreservesSavedEvidence() = runTest {
        val f = Fixture()
        f.inspect()
        val before = f.stored
        val reads = f.reads
        f.allowed = false
        var cancelled = false
        try { f.inspect() } catch (_: CancellationException) { cancelled = true }
        assertThat(cancelled).isTrue()
        assertThat(f.reads).isEqualTo(reads)
        assertThat(f.stored).isEqualTo(before)
        assertThat(f.repository.canReuse(ContentIndexCandidate(f.record, "/Downloads"), com.pocketsteward.app.content.ContentInspectionBudget.FULL)).isFalse()
        assertThat(f.repository.search("Lilith", listOf("/Downloads"))).isEmpty()
    }
    companion object {
        private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handle(method.name, args ?: emptyArray()) } as T
    }
}
