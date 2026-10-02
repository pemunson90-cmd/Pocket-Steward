package com.pocketsteward.app.evidence.analysis

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.storage.StorageAccessMode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceAnalysisRunnerTest {
    @get:Rule val directory = TemporaryFolder()
    private fun request(count: Int = 3) = EvidenceAnalysisRequest(UUID.randomUUID().toString(), StorageAccessMode.DIRECT, null, true, true, false,
        List(count) { n -> EvidenceAnalysisSource(FileRecord(stableRef = "/Download/Uncertain/$n.jpg", displayName = "$n.jpg", extension = "jpg", mimeType = "image/jpeg",
            absolutePathOrUri = "/Download/Uncertain/$n.jpg", parentRef = "/Download/Uncertain", sizeBytes = 100, createdAt = null, modifiedAt = 99,
            lastScannedAt = 0, isDirectory = false, isHidden = false), "/Download") })
    private fun store() = EvidenceAnalysisStore(directory.newFolder())

    @Test fun pauseSurvivesStoreRecreationAndResumeStartsAtNextFile() = runTest {
        val folder = directory.newFolder()
        val store = EvidenceAnalysisStore(folder)
        val request = request()
        store.saveRequest(request)
        val visited = mutableListOf<String>()
        val paused = EvidenceAnalysisRunner(store).run(request, { true }, { source ->
            visited += source.record.stableRef
            store.update(request.id) { it.copy(pauseRequested = true) }
            EvidenceAnalysisOutcome.ANALYZED
        })
        assertThat(paused.status).isEqualTo(EvidenceAnalysisStatus.PAUSED)
        assertThat(paused.processed).isEqualTo(1)
        val reopened = EvidenceAnalysisStore(folder)
        assertThat(reopened.latest()).isEqualTo(paused)
        reopened.update(request.id) { it.copy(pauseRequested = false) }
        val done = EvidenceAnalysisRunner(reopened).run(reopened.request(request.id), { true }, { source ->
            visited += source.record.stableRef; EvidenceAnalysisOutcome.REUSED
        })
        assertThat(visited).containsExactlyElementsIn(request.sources.map { it.record.stableRef }).inOrder()
        assertThat(done.status).isEqualTo(EvidenceAnalysisStatus.COMPLETED)
        assertThat(done.analyzed).isEqualTo(1)
        assertThat(done.reused).isEqualTo(2)
    }

    @Test fun interruptionDoesNotAdvanceCursorPastAnUnfinishedFile() = runTest {
        val store = store(); val request = request(); store.saveRequest(request)
        val failure = runCatching { EvidenceAnalysisRunner(store).run(request, { true }, { throw CancellationException("Android stopped worker") }) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(store.progress(request.id).status).isEqualTo(EvidenceAnalysisStatus.PAUSED)
        assertThat(store.progress(request.id).processed).isEqualTo(0)
        val done = EvidenceAnalysisRunner(store).run(request, { true }, { EvidenceAnalysisOutcome.UNAVAILABLE })
        assertThat(done.unavailable).isEqualTo(3)
        assertThat(done.status).isEqualTo(EvidenceAnalysisStatus.COMPLETED)
    }

    @Test fun unavailableAndChangedFilesCannotStarveLaterCandidates() = runTest {
        val store = store(); val request = request(); store.saveRequest(request)
        var active = 0; var maximumActive = 0
        val outcomes = ArrayDeque(listOf(EvidenceAnalysisOutcome.UNAVAILABLE, EvidenceAnalysisOutcome.CHANGED, EvidenceAnalysisOutcome.ANALYZED))
        val done = EvidenceAnalysisRunner(store).run(request, { true }, {
            active++; maximumActive = maxOf(maximumActive, active)
            val result = outcomes.removeFirst(); active--; result
        })
        assertThat(done.processed).isEqualTo(3)
        assertThat(done.changed).isEqualTo(1)
        assertThat(done.unavailable).isEqualTo(1)
        assertThat(done.analyzed).isEqualTo(1)
        assertThat(maximumActive).isEqualTo(1)
    }

    @Test fun revokedPrivacyOrAccessStopsBeforeAnotherFileRead() = runTest {
        val store = store(); val request = request(); store.saveRequest(request)
        var permitted = true; var reads = 0
        val paused = EvidenceAnalysisRunner(store).run(request, { permitted }, {
            reads++; permitted = false; EvidenceAnalysisOutcome.PARTIAL
        })
        assertThat(reads).isEqualTo(1)
        assertThat(paused.processed).isEqualTo(1)
        assertThat(paused.status).isEqualTo(EvidenceAnalysisStatus.PAUSED)
        assertThat(paused.partial).isEqualTo(1)
    }

    @Test fun sixteenThousandSourceSnapshotIsSavedOnceAndReloadsOriginalMetadata() {
        val store = store(); val request = request(16_000); store.saveRequest(request)
        assertThat(store.request(request.id)).isEqualTo(request)
        assertThat(store.latest()?.total).isEqualTo(16_000)
    }

    @Test fun corruptRequestAndCursorAreRejectedInsteadOfSilentlyResettingProgress() {
        val folder = directory.newFolder(); val store = EvidenceAnalysisStore(folder); val request = request(); store.saveRequest(request)
        val file = java.io.File(folder, "${request.id}.request")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertThat(runCatching { store.request(request.id) }.isFailure).isTrue()
        java.io.File(folder, "${request.id}.progress").writeText("broken")
        assertThat(runCatching { store.progress(request.id) }.isFailure).isTrue()
    }
}
