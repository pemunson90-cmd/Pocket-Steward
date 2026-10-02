package com.pocketsteward.app.evidence.analysis

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.FilingFolderIndex
import com.pocketsteward.app.plan.SourcePrecondition
import com.pocketsteward.app.storage.StorageAccessMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceAnalysisFoldersTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(ref: String, parent: String?, dir: Boolean = false, size: Long = 10) = FileRecord(
        stableRef = ref, displayName = ref.substringAfterLast('/'), extension = if (dir) "" else "txt", mimeType = null,
        absolutePathOrUri = ref, parentRef = parent, sizeBytes = size, createdAt = null, modifiedAt = 99,
        lastScannedAt = 0, isDirectory = dir, isHidden = false)
    private fun artifact(record: FileRecord) = FilingArtifact(record.stableRef, record.displayName, record.extension,
        record.sizeBytes, record.createdAt, record.modifiedAt, record.parentRef, isDirectory = record.isDirectory)
    private val baseline = SourcePrecondition(160_000, 99, "a".repeat(64), 16_000)

    @Test fun sixteenThousandDescendantsBelongToOneUnitWithoutBecomingReviewMoves() {
        val folder = record("/Download/bundle", "/Download", true)
        val children = List(16_000) { record("/Download/bundle/$it.txt", folder.stableRef) }
        val loose = record("/Download/loose.txt", "/Download", size = 20)
        val indexed = listOf(folder, loose.copy(sizeBytes = 999)) + children + children.first()
        val planned = EvidenceAnalysisSourcePlanner.forReview(listOf(artifact(folder), artifact(loose)), indexed,
            mapOf(folder.stableRef to baseline), true, true) { "/Download" }
        assertThat(planned.sources).hasSize(16_001)
        assertThat(planned.folders).containsExactly(EvidenceAnalysisFolder(folder.stableRef, baseline))
        assertThat(planned.sources.count { it.folderUnitRef == folder.stableRef }).isEqualTo(16_000)
        assertThat(planned.sources.single { it.record.stableRef == loose.stableRef }.record.sizeBytes).isEqualTo(20)
        assertThat(FilingFolderIndex(indexed, setOf(folder.stableRef)).descendants(indexed)[folder.stableRef]).hasSize(16_000)
    }

    @Test fun opaqueProviderIdsUseParentGraphAndCyclesAndMissingAncestorsStayOutside() {
        val folder = record("content://provider/tree/root/document/opaqueA", null, true)
        val child = record("content://provider/tree/root/document/entirelyDifferent", folder.stableRef)
        val prefixPretender = record(folder.stableRef + "/looks-related.txt", "content://provider/document/outside")
        val cycleA = record("a", "b", true); val cycleB = record("b", "a", true)
        val rows = listOf(folder, child, prefixPretender, cycleA, cycleB)
        val graph = FilingFolderIndex(rows, setOf(folder.stableRef))
        assertThat(graph.ownerOf(child.stableRef)).isEqualTo(folder.stableRef)
        assertThat(graph.ownerOf(folder.stableRef)).isNull()
        assertThat(graph.ownerOf(prefixPretender.stableRef)).isNull()
        assertThat(graph.ownerOf("a")).isNull()
        assertThat(graph.ownerOf("b")).isNull()
    }

    @Test fun deepIndexedTreesDoNotRecurseOnTheJvmStack() {
        val rows = List(16_000) { record("node$it", if (it == 0) null else "node${it - 1}", true) }
        val graph = FilingFolderIndex(rows, setOf("node0"))
        assertThat(graph.ownerOf("node15999")).isEqualTo("node0")
        assertThat(graph.ownerOf("node8000")).isEqualTo("node0")
        assertThat(graph.ownerOf("node0")).isNull()
    }

    @Test fun folderGuardCoalescesTraversalAndRechecksTheOriginalBaselineAfterRestart() = runTest {
        var captures = 0
        val folder = EvidenceAnalysisFolder("/Download/bundle", baseline)
        val source = EvidenceAnalysisSource(record("/Download/bundle/1.txt", folder.ref), "/Download", folder.ref)
        val first = EvidenceAnalysisFolderGuard(listOf(folder)) { captures++; baseline }
        repeat(16_000) { assertThat(first.refusal(source)).isNull() }
        assertThat(captures).isEqualTo(1)
        val resumed = EvidenceAnalysisFolderGuard(listOf(folder)) { captures++; baseline.copy(directoryEntryCount = 16_001) }
        assertThat(resumed.refusal(source)).isEqualTo(EvidenceAnalysisOutcome.CHANGED)
        assertThat(resumed.refusal(source)).isEqualTo(EvidenceAnalysisOutcome.CHANGED)
        assertThat(captures).isEqualTo(2)
        val cancelled = EvidenceAnalysisFolderGuard(listOf(folder)) { throw CancellationException() }
        assertThat(runCatching { cancelled.refusal(source) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test fun requestsRetainFolderSnapshotsAndAutomaticChargingPolicyAcrossRestart() {
        val folder = EvidenceAnalysisFolder("/Download/bundle", baseline)
        val request = EvidenceAnalysisRequest(UUID.randomUUID().toString(), StorageAccessMode.DIRECT, null, true, true, false,
            listOf(EvidenceAnalysisSource(record("/Download/bundle/1.txt", folder.ref), "/Download", folder.ref)), listOf(folder), true)
        val directory = temporary.newFolder(); val store = EvidenceAnalysisStore(directory); store.saveRequest(request)
        assertThat(EvidenceAnalysisStore(directory).request(request.id)).isEqualTo(request)
        assertThat(request.sameInventoryAs(request.copy(id = UUID.randomUUID().toString(), automatic = false))).isTrue()
        assertThat(request.sameInventoryAs(request.copy(sources = request.sources.map { it.copy(record = it.record.copy(sizeBytes = 100)) }))).isFalse()
        val revised = request.copy(observedRevision = "${UUID.randomUUID()}:12")
        assertThat(request.sameInventoryAs(revised)).isFalse()
        val other = EvidenceAnalysisStore(temporary.newFolder()); other.saveRequest(revised)
        assertThat(other.request(revised.id).observedRevision).isEqualTo(revised.observedRevision)
        assertThat(runCatching { request.copy(folders = emptyList()).validate() }.isFailure).isTrue()
    }

    @Test fun originalVersionOneLooseFileRequestRemainsReadable() {
        val id = UUID.randomUUID().toString(); val directory = temporary.newFolder()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeUTF(id); out.writeUTF("DIRECT"); out.writeBoolean(false)
            out.writeBoolean(true); out.writeBoolean(true); out.writeBoolean(false); out.writeInt(1)
            out.writeUTF("/Download"); out.writeUTF("/Download/a.txt"); out.writeUTF("a.txt"); out.writeUTF("txt")
            out.writeBoolean(false); out.writeBoolean(true); out.writeUTF("/Download")
            out.writeLong(10); out.writeBoolean(true); out.writeLong(99)
        }
        val payload = bytes.toByteArray()
        File(directory, "$id.request").writeBytes(MessageDigest.getInstance("SHA-256").digest(payload) + payload)
        val request = EvidenceAnalysisStore(directory).request(id)
        assertThat(request.folders).isEmpty(); assertThat(request.automatic).isFalse()
        assertThat(request.sources.single().folderUnitRef).isNull()
        assertThat(request.sources.single().record.stableRef).isEqualTo("/Download/a.txt")
    }

    @Test fun supersededInventorySnapshotsHaveBoundedRetention() {
        val directory = temporary.newFolder(); val store = EvidenceAnalysisStore(directory)
        val requests = List(6) {
            EvidenceAnalysisRequest(UUID.randomUUID().toString(), StorageAccessMode.DIRECT, null, true, false, false,
                listOf(EvidenceAnalysisSource(record("/Download/$it.txt", "/Download"), "/Download")))
        }
        requests.forEach { store.saveRequest(it) }
        assertThat(directory.listFiles().orEmpty().count { it.extension == "request" }).isEqualTo(3)
        assertThat(directory.listFiles().orEmpty().count { it.extension == "progress" }).isEqualTo(3)
        assertThat(store.request(requests.last().id)).isEqualTo(requests.last())
        assertThat(store.latest()?.id).isEqualTo(requests.last().id)
    }
}
