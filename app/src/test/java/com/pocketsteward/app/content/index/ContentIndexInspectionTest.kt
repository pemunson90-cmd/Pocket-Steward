package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import org.junit.Test

class ContentIndexInspectionTest {
    @Test fun partialInspectionCachesFreshTextWithoutRemovingUnrelatedDocumentsOrMarkingRootComplete() = runTest {
        val documents = mutableMapOf<String, IndexedDocument>()
        val segments = mutableMapOf<String, List<IndexedSegment>>()
        var bytes = "Lilith manuscript chapter one".toByteArray()
        var reads = 0
        val gateway = proxy<StorageGateway> { name, _ ->
            check(name == "openRead") { "Unexpected storage call: $name" }
            reads++
            bytes.inputStream()
        }
        val dao = proxy<ContentIndexDao> { name, args ->
            when (name) {
                "getDocument" -> documents[args[0]]
                "replaceDocument" -> {
                    val doc = args[0] as IndexedDocument
                    documents[doc.stableRef] = doc
                    @Suppress("UNCHECKED_CAST")
                    segments[doc.stableRef] = args[1] as List<IndexedSegment>
                    Unit
                }
                "getExcerptSegments" -> segments[args[0]].orEmpty().take(args[1] as Int)
                else -> error("Partial enrichment must not prune rows or alter root completeness: $name")
            }
        }
        val repository = ContentIndexRepository(dao, ContentInspector(gateway))
        val record = record("/inbox/Uncertain/file.txt", bytes.size.toLong(), 1)
        val first = repository.ensureDocument(ContentIndexCandidate(record, "/inbox"))
        assertThat(first.reused).isFalse()
        assertThat(repository.excerpt(record.stableRef)).contains("Lilith")
        assertThat(repository.ensureDocument(ContentIndexCandidate(record, "/inbox")).reused).isTrue()
        assertThat(reads).isEqualTo(1)
        val other = record("/inbox/other.txt", bytes.size.toLong(), 1)
        repository.ensureDocument(ContentIndexCandidate(other, "/inbox"))
        bytes = "NSTL research outline".toByteArray()
        repository.ensureDocument(ContentIndexCandidate(record.copy(sizeBytes = bytes.size.toLong(), modifiedAt = 2), "/inbox"))
        assertThat(repository.excerpt(record.stableRef)).contains("NSTL")
        assertThat(repository.excerpt(record.stableRef)).doesNotContain("Lilith")
        assertThat(documents.keys).containsExactly(record.stableRef, other.stableRef)
    }

    @Test fun unreadableDocumentRecordsFailureWithoutFabricatingText() = runTest {
        var stored: IndexedDocument? = null
        val dao = proxy<ContentIndexDao> { name, args ->
            when (name) {
                "getDocument" -> stored
                "replaceDocument" -> { stored = args[0] as IndexedDocument; assertThat(args[1] as List<*>).isEmpty(); Unit }
                else -> error(name)
            }
        }
        val gateway = proxy<StorageGateway> { _, _ -> error("Permission revoked") }
        val result = ContentIndexRepository(dao, ContentInspector(gateway)).ensureDocument(ContentIndexCandidate(record("/inbox/a.txt", 10, 1), "/inbox"))
        assertThat(result.document.extractionStatus).isEqualTo(IndexedExtractionStatus.FAILED.name)
        assertThat(result.document.extractionError).contains("Permission revoked")
    }

    private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handle(method.name, args ?: emptyArray()) } as T

    private fun record(ref: String, size: Long, modified: Long) = FileRecord(
        stableRef = ref, displayName = ref.substringAfterLast('/'), extension = "txt", mimeType = "text/plain",
        absolutePathOrUri = ref, parentRef = ref.substringBeforeLast('/'), sizeBytes = size, createdAt = null,
        modifiedAt = modified, lastScannedAt = 1, isDirectory = false, isHidden = false,
    )
}
