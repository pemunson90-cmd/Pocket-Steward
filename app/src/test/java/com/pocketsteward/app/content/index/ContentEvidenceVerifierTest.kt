package com.pocketsteward.app.content.index

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy

class ContentEvidenceVerifierTest {
    private val ref = "/Download/a.txt"
    private val snapshot = ContentEvidenceSnapshot(ref, "a.txt", 100, 20)
    private fun metadata(name: String = "a.txt", size: Long = 100, date: Long? = 20, directory: Boolean = false) =
        FileMetadata(FileRef.Direct(ref), name, "txt", "text/plain", size, null, date, directory, false)

    @Test fun unchangedSegmentsShareOneMetadataCheck() = runTest {
        var stats = 0
        val verified = ContentEvidenceVerifier { stats++; metadata() }.currentRefs(List(100) { snapshot })
        assertThat(verified).containsExactly(ref)
        assertThat(stats).isEqualTo(1)
    }

    @Test fun changedSizeDateNameOrKindCannotSupplyCachedEvidence() = runTest {
        for (live in listOf(metadata(size = 101), metadata(date = 21), metadata(name = "b.txt"), metadata(directory = true), metadata(date = null))) {
            assertThat(ContentEvidenceVerifier { live }.currentRefs(listOf(snapshot))).isEmpty()
        }
    }

    @Test fun undatedOrUnmeasuredSnapshotsAreNotAssumedCurrent() = runTest {
        val verifier = ContentEvidenceVerifier { error("An unverifiable cache must not perform a stat") }
        assertThat(verifier.currentRefs(listOf(snapshot.copy(modifiedAt = null), snapshot.copy(size = null)))).isEmpty()
    }

    @Test fun missingAndRevokedSourcesDoNotBlockOtherDocuments() = runTest {
        val other = snapshot.copy(stableRef = "/Download/b.txt", name = "b.txt")
        val verifier = ContentEvidenceVerifier { path ->
            if (path == ref) throw SecurityException("Permission revoked") else metadata(name = "b.txt").copy(ref = FileRef.Direct(other.stableRef))
        }
        assertThat(verifier.currentRefs(listOf(snapshot, other))).containsExactly(other.stableRef)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAnEmptySearch() = runTest {
        var propagated = false
        try { ContentEvidenceVerifier { throw CancellationException("Stop") }.currentRefs(listOf(snapshot)) }
        catch (_: CancellationException) { propagated = true }
        assertThat(propagated).isTrue()
    }

    @Test fun inconsistentSnapshotsForOneFileDoNotAuthorizeEitherVersion() = runTest {
        assertThat(ContentEvidenceVerifier { metadata() }.currentRefs(listOf(snapshot, snapshot.copy(size = 200)))).isEmpty()
    }

    @Test fun searchChecksBoundedSamplesAndMetadataWithoutMutatingTheIndex() = runTest {
        var stats = 0
        val bytes = ByteArray(100) { 65 }
        val fingerprint = com.pocketsteward.app.evidence.EvidenceFingerprint.read(bytes.inputStream(), 100)
        val rows = listOf(
            IndexedSearchRow(1, ref, "/Download", "a.txt", "/Download", "txt", "DOCUMENT", 100, 20, "TEXT", null, false, "Lilith", quickFingerprint = fingerprint),
            IndexedSearchRow(2, "/Download/b.txt", "/Download", "b.txt", "/Download", "txt", "DOCUMENT", 100, 20, "TEXT", null, false, "Old Lilith", quickFingerprint = fingerprint),
        )
        val dao = proxy<ContentIndexDao> { name, _ -> check(name == "searchRows"); rows }
        val gateway = proxy<StorageGateway> { name, args ->
            when (name) {
                "stat" -> { stats++; if (args[0] == FileRef.Direct(ref)) metadata() else metadata(name = "b.txt", date = 21) }
                "openRead" -> { check(args[0] == FileRef.Direct(ref)); bytes.inputStream() }
                else -> error("Search must not mutate storage: $name")
            }
        }
        val result = ContentIndexRepository(dao, ContentInspector(gateway)).search("Lilith", listOf("/Download"))
        assertThat(result.map { it.stableRef }).containsExactly(ref)
        assertThat(stats).isEqualTo(3)
    }

    @Test fun sameMetadataWithDifferentSampleCannotSupplySearchOrAskEvidence() = runTest {
        val fingerprint = com.pocketsteward.app.evidence.EvidenceFingerprint.PREFIX + "prefix:old"
        val verifier = ContentEvidenceVerifier(observeFingerprint = { fingerprint + "changed" }) { metadata() }
        assertThat(verifier.currentRefs(listOf(snapshot.copy(quickFingerprint = fingerprint)))).isEmpty()
    }
    @Test fun legacyCachesMustBeReindexedBeforeSupplyingSampleVerifiedEvidence() = runTest {
        var samples = 0
        val verifier = ContentEvidenceVerifier(observeFingerprint = { samples++; "unused" }) { metadata() }
        assertThat(verifier.currentRefs(listOf(snapshot))).isEmpty()
        assertThat(samples).isEqualTo(0)
    }
    @Test fun sourceChangeAfterSampleCannotSupplyEvidence() = runTest {
        val fingerprint = com.pocketsteward.app.evidence.EvidenceFingerprint.PREFIX + "prefix:unchanged"
        var stats = 0
        val verifier = ContentEvidenceVerifier(observeFingerprint = { fingerprint }) {
            stats++; metadata(date = if (stats == 1) 20 else 21)
        }
        assertThat(verifier.currentRefs(listOf(snapshot.copy(quickFingerprint = fingerprint)))).isEmpty()
    }
    private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handle(method.name, args ?: emptyArray()) } as T
}
