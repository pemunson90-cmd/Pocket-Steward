package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ContentInspectionBudget
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy

class ContentIndexScopesTest {
    @Test fun overlappingRefreshExtractsOnceAndPruningOneRootRetainsOtherCoverage() = runTest {
        val fixture = Fixture()
        val rootA = "/Downloads"; val rootB = "/Downloads/Uncertain"
        val result = fixture.repository.refresh(listOf(ContentIndexCandidate(fixture.record, rootA), ContentIndexCandidate(fixture.record, rootB)), listOf(rootA, rootB))
        assertThat(result.extracted).isEqualTo(1)
        assertThat(result.reused).isEqualTo(1)
        assertThat(fixture.scopes).containsExactly(IndexedDocumentScope(fixture.record.stableRef, rootA), IndexedDocumentScope(fixture.record.stableRef, rootB))
        fixture.repository.refresh(emptyList(), listOf(rootA))
        assertThat(fixture.documents).containsKey(fixture.record.stableRef)
        assertThat(fixture.repository.excerpt(fixture.record.stableRef)).contains("Lilith")
        assertThat(fixture.scopes).containsExactly(IndexedDocumentScope(fixture.record.stableRef, rootB))
        fixture.repository.refresh(emptyList(), listOf(rootB))
        assertThat(fixture.documents).isEmpty()
    }
    @Test fun boundedFilingCacheCannotPretendToBeFullIndexAndFullCacheCanServeFiling() = runTest {
        val fixture = Fixture("Lilith " + "x".repeat(50_000))
        val candidate = ContentIndexCandidate(fixture.record, "/Downloads")
        val quick = fixture.repository.ensureDocument(candidate, ContentInspectionBudget.FILING)
        assertThat(quick.document.extractionProfile).isEqualTo("FILING")
        assertThat(quick.document.coverageComplete).isFalse()
        val full = fixture.repository.ensureDocument(candidate)
        assertThat(full.reused).isFalse()
        assertThat(full.document.extractionProfile).isEqualTo("FULL")
        assertThat(full.document.coverageComplete).isTrue()
        assertThat(fixture.repository.ensureDocument(candidate, ContentInspectionBudget.FILING).reused).isTrue()
        assertThat(fixture.reads).isEqualTo(2)
    }
    @Test fun rootFilterAcceptsEveryMembershipWithoutDuplicatingOneResult() {
        val row = IndexedSearchRow(1, "/Downloads/Uncertain/a.txt", "/Downloads", "a.txt", "/Downloads/Uncertain", "txt", "DOCUMENT", 12, 100, "PLAIN_TEXT", null, false, "Lilith", matchingSourceRoots = "/Downloads\n/Downloads/Uncertain", extractionProfile = "FILING")
        val grouped = ContentSearchView.group(listOf(row), "Lilith")
        assertThat(ContentSearchView.apply(grouped, ContentSearchSort.RELEVANCE, ContentSearchFilters(sourceRoots = setOf("/Downloads/Uncertain")))).hasSize(1)
        assertThat(grouped.single().sourceRoots).containsExactly("/Downloads", "/Downloads/Uncertain")
        assertThat(grouped.single().coverageComplete).isFalse()
    }
    private class Fixture(text: String = "Lilith manuscript notes") {
        val documents = linkedMapOf<String, IndexedDocument>()
        val segments = linkedMapOf<String, List<IndexedSegment>>()
        val scopes = linkedSetOf<IndexedDocumentScope>()
        val states = linkedMapOf<String, ContentIndexState>()
        var reads = 0
        val bytes = text.toByteArray()
        val record = FileRecord(stableRef = "/Downloads/Uncertain/a.txt", displayName = "a.txt", extension = "txt", mimeType = "text/plain", absolutePathOrUri = "/Downloads/Uncertain/a.txt", parentRef = "/Downloads/Uncertain", sizeBytes = bytes.size.toLong(), createdAt = null, modifiedAt = 100, lastScannedAt = 100, isDirectory = false, isHidden = false)
        val dao = proxy<ContentIndexDao> { name, args ->
            when (name) {
                "getDocument" -> documents[args[0]]
                "getStableRefsForRoot" -> scopes.filter { it.sourceRoot == args[0] }.map { it.stableRef }
                "putScope" -> { scopes += args[0] as IndexedDocumentScope; Unit }
                "replaceDocument" -> {
                    val doc = args[0] as IndexedDocument
                    documents[doc.stableRef] = doc
                    @Suppress("UNCHECKED_CAST")
                    segments[doc.stableRef] = args[1] as List<IndexedSegment>
                    scopes += IndexedDocumentScope(doc.stableRef, doc.sourceRoot)
                    Unit
                }
                "removeFromRoot" -> {
                    val ref = args[0] as String; scopes -= IndexedDocumentScope(ref, args[1] as String)
                    if (scopes.none { it.stableRef == ref }) { documents.remove(ref); segments.remove(ref) }
                    Unit
                }
                "putState" -> { val state = args[0] as ContentIndexState; states[state.sourceRoot] = state; Unit }
                "getState" -> states[args[0]]
                "getExcerptSegments" -> segments[args[0]].orEmpty().take(args[1] as Int)
                else -> error(name)
            }
        }
        val gateway = proxy<StorageGateway> { name, _ ->
            when (name) {
                "stat" -> com.pocketsteward.app.storage.FileMetadata(com.pocketsteward.app.storage.FileRef.Direct(record.stableRef), record.displayName,
                    "txt", "text/plain", record.sizeBytes, null, record.modifiedAt, false, false)
                "openRead" -> { reads++; bytes.inputStream() }
                else -> error(name)
            }
        }
        val repository = ContentIndexRepository(dao, ContentInspector(gateway))
    }
    companion object {
        private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handle(method.name, args ?: emptyArray()) } as T
    }
}
