package com.pocketsteward.app.stabilization

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.content.ContentInspectionBudget
import com.pocketsteward.app.content.ContentInspector
import com.pocketsteward.app.content.index.*
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.dedupe.DuplicateDetector
import com.pocketsteward.app.plan.PlanSelection
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.*
import com.pocketsteward.app.ui.scan.ScanUiState
import com.pocketsteward.app.ui.scan.ScanViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy

/** Regressions for bugs established by the dev14 audit. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditStabilizationTest {
    @Test fun cancellingFilingClearsBusyState() {
        // Invoke the real cancellation and Idle-routing methods; bypass Android-only constructor dependencies.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val vm = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ScanViewModel::class.java) as ScanViewModel
        fun field(name: String, value: Any) {
            ScanViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
        }
        val working = ScanUiState.Working("Planning", "audit")
        val events = MutableStateFlow<ScanUiState>(working)
        val busy = MutableStateFlow<ScanUiState.Working?>(working)
        val planning = Job()
        field("_uiState", events); field("_busy", busy); field("busy", busy); field("filingPlanningJob", planning)
        vm.cancelFilingWork()
        assertThat(planning.isCancelled).isTrue()
        assertThat(events.value).isEqualTo(ScanUiState.Idle)
        val route = ScanViewModel::class.java.declaredMethods.single { it.name == "routeToDestination" }.apply { isAccessible = true }
        route.invoke(vm, ScanUiState.Idle, null)
        assertThat(vm.busy.value).isNull()

    }

    private fun record(name: String, text: String) = FileRecord(
        stableRef = "/Downloads/$name", displayName = name, extension = "txt", mimeType = "text/plain",
        absolutePathOrUri = "/Downloads/$name", parentRef = "/Downloads", sizeBytes = text.toByteArray().size.toLong(),
        createdAt = null, modifiedAt = 100, lastScannedAt = 100, isDirectory = false, isHidden = false)

    private class Fixture(val records: List<FileRecord>, val text: String) {
        val documents = linkedMapOf<String, IndexedDocument>()
        val segments = linkedMapOf<String, List<IndexedSegment>>()
        val scopes = linkedSetOf<IndexedDocumentScope>()
        var job: ContentIndexJob? = null
        var state: ContentIndexState? = null
        val dao = proxy<ContentIndexDao> { name, args -> when (name) {
            "getDocument" -> documents[args[0]]
            "getJob" -> job
            "putJob" -> { job = args[0] as ContentIndexJob; Unit }
            "getState" -> state
            "putState" -> { state = args[0] as ContentIndexState; Unit }
            "putScope" -> { scopes += args[0] as IndexedDocumentScope; Unit }
            "getStableRefsForRoot" -> scopes.filter { it.sourceRoot == args[0] }.map { it.stableRef }
            "removeFromRoot" -> { scopes.remove(IndexedDocumentScope(args[0] as String, args[1] as String)); Unit }
            "replaceDocument" -> {
                val doc = args[0] as IndexedDocument; documents[doc.stableRef] = doc
                @Suppress("UNCHECKED_CAST")
                segments[doc.stableRef] = args[1] as List<IndexedSegment>
                scopes += IndexedDocumentScope(doc.stableRef, doc.sourceRoot); Unit
            }
            "clearAll" -> { documents.clear(); segments.clear(); scopes.clear(); job = null; state = null; Unit }
            else -> error(name)
        } }
        val gateway = proxy<StorageGateway> { name, args -> when (name) {
            "stat" -> {
                val r = records.first { it.stableRef == (args[0] as FileRef).rawValue() }
                FileMetadata(FileRef.Direct(r.stableRef), r.displayName, r.extension, r.mimeType, r.sizeBytes, null, r.modifiedAt, false, false)
            }
            "openRead" -> text.byteInputStream()
            else -> error(name)
        } }
        val repository = ContentIndexRepository(dao, ContentInspector(gateway))
    }

    @Test fun resumeReconcilesNewDocumentBeforeSavedCursor() = runTest {
        val text = "Project Lilith"
        val a = record("a.txt", text); val b = record("b.txt", text); val c = record("c.txt", text)
        val f = Fixture(listOf(a, b, c), text)
        fun candidates(rs: List<FileRecord>) = rs.map { ContentIndexCandidate(it, "/Downloads") }
        f.repository.refreshRootResumable(candidates(listOf(b, c)), "/Downloads", shouldPause = { f.documents.size == 1 })
        assertThat(f.job!!.status).isEqualTo("PAUSED")
        assertThat(f.job!!.cursorRef).isEqualTo(b.stableRef)
        val completed = f.repository.refreshRootResumable(candidates(listOf(a, b, c)), "/Downloads")
        assertThat(completed.status).isEqualTo("COMPLETED")
        assertThat(completed.processedCount).isEqualTo(3)
        assertThat(f.documents.keys).containsExactly(a.stableRef, b.stableRef, c.stableRef)

    }

    @Test fun concurrentFilingAndFullIndexRetainFullContent() = runTest {
        val text = "Project Lilith " + "x".repeat(50_000)
        val r = record("a.txt", text); val f = Fixture(listOf(r), text)
        val extractionStarted = CompletableDeferred<Unit>(); val releaseExtraction = CompletableDeferred<Unit>()
        var reads = 0
        val heldGateway = object : StorageGateway by f.gateway {
            override suspend fun openRead(ref: FileRef): java.io.InputStream {
                if (++reads == 2) { extractionStarted.complete(Unit); releaseExtraction.await() }
                return text.byteInputStream()
            }
        }
        val filing = ContentIndexRepository(f.dao, ContentInspector(heldGateway))
        val pending = async { filing.ensureDocument(ContentIndexCandidate(r, "/Downloads"), ContentInspectionBudget.FILING) }
        extractionStarted.await()
        val full = async { f.repository.refreshRootResumable(listOf(ContentIndexCandidate(r, "/Downloads")), "/Downloads") }
        runCurrent()
        assertThat(full.isCompleted).isFalse()
        releaseExtraction.complete(Unit); pending.await(); full.await()
        assertThat(f.documents[r.stableRef]!!.extractionProfile).isEqualTo("FULL")
        assertThat(f.documents[r.stableRef]!!.coverageComplete).isTrue()
        assertThat(f.state!!.completed).isTrue()
    }

    @Test fun identicalFilesAreFoundWithLegalProviderShortReads() = runTest {
        val text = "identical file contents"
        val a = record("a.txt", text); val b = record("b.txt", text)
        val bytes = text.toByteArray()
        val gateway = proxy<StorageGateway> { name, args -> when (name) {
            "openRead" -> if ((args[0] as FileRef).rawValue() == a.stableRef) ByteArrayInputStream(bytes)
                else object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
                }
            "stat" -> FileMetadata(args[0] as FileRef, "file", "txt", "text/plain", bytes.size.toLong(), null, 100, false, false)
            else -> error(name)
        } }
        assertThat(DuplicateDetector(gateway).findDuplicates(listOf(a, b))).hasSize(1)

    }

    @Test fun clearDuringExtractionCannotRestoreTheClearedCache() = runTest {
        val text = "Project Lilith"; val r = record("a.txt", text); val f = Fixture(listOf(r), text)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var reads = 0
        val gateway = object : StorageGateway by f.gateway {
            override suspend fun openRead(ref: FileRef): java.io.InputStream {
                if (++reads == 2) { started.complete(Unit); release.await() }
                return text.byteInputStream()
            }
        }
        val repository = ContentIndexRepository(f.dao, ContentInspector(gateway))
        val pending = async { repository.ensureDocument(ContentIndexCandidate(r, "/Downloads")) }
        started.await()
        repository.clear()
        release.complete(Unit)
        try { pending.await(); error("Old writer restored a cleared cache") } catch (_: CancellationException) { }
        assertThat(f.documents).isEmpty()
        assertThat(f.segments).isEmpty()
        // The same repository and DAO remain usable; no closed database references.
        assertThat(repository.ensureDocument(ContentIndexCandidate(r, "/Downloads")).document.extractionStatus).isEqualTo("INDEXED")
    }

    @Test fun foregroundAdmissionRetainsNewRootsAndSeparatesModes() {
        val queue = ContentIndexRequestQueue()
        queue.add(listOf("/A/"), StorageAccessMode.DIRECT)
        assertThat(queue.take()).isEqualTo(ContentIndexRequestQueue.Request("/A", StorageAccessMode.DIRECT))
        queue.add(listOf("/B", "/B/"), StorageAccessMode.DIRECT)
        queue.add(listOf("content://tree/B"), StorageAccessMode.SAF)
        assertThat(queue.take()).isEqualTo(ContentIndexRequestQueue.Request("/B", StorageAccessMode.DIRECT))
        assertThat(queue.take()).isEqualTo(ContentIndexRequestQueue.Request("content://tree/B", StorageAccessMode.SAF))
        assertThat(queue.take()).isNull()
    }

    @Test fun duplicateRootRunnersWaitAndClearRejectsAnOlderQueuedRunner() = runTest {
        val coordination = ContentIndexCoordination()
        val epoch = coordination.epoch()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { coordination.root("/Downloads/", epoch) { started.complete(Unit); release.await() } }
        started.await()
        var secondEntered = false
        val second = async { coordination.root("/Downloads", epoch) { secondEntered = true } }
        runCurrent()
        assertThat(secondEntered).isFalse()
        coordination.clear { }
        release.complete(Unit); first.await()
        try { second.await(); error("An old queued runner survived clear") } catch (_: CancellationException) { }
        assertThat(secondEntered).isFalse()
        coordination.root("/Downloads", coordination.epoch()) { secondEntered = true }
        assertThat(secondEntered).isTrue()
    }

    @Test fun retiringIndexRunnerCannotPauseANewJobOrAnActiveRoot() = runTest {
        val f = Fixture(emptyList(), "")
        val repository = f.repository
        repository.queueRoot("/Downloads", 1)
        val oldEpoch = repository.lifecycleEpoch()
        repository.clear()
        repository.queueRoot("/Downloads", 2)
        try { repository.markPaused("/Downloads", "old runner", oldEpoch); error("Old runner survived clear") }
        catch (_: CancellationException) { }
        assertThat(f.job!!.status).isEqualTo("QUEUED")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val active = async {
            ContentIndexCoordination.Shared.root("/Downloads", repository.lifecycleEpoch()) { started.complete(Unit); release.await() }
        }
        started.await()
        repository.markPaused("/Downloads", "another runner stopped")
        assertThat(f.job!!.status).isEqualTo("QUEUED")
        release.complete(Unit); active.await()
        repository.markPaused("/Downloads", "current runner stopped")
        assertThat(f.job!!.status).isEqualTo("PAUSED")
    }

    @Test fun cancelledRunnerHandsOffRequestsThatArriveDuringCleanup() = runTest {
        val queue = ContentIndexRequestQueue()
        queue.add(listOf("/A"), StorageAccessMode.DIRECT)
        assertThat(queue.attachRunner()).isTrue()
        val cleanupStarted = CompletableDeferred<Unit>(); val finishCleanup = CompletableDeferred<Unit>()
        var handoff = emptyList<ContentIndexRequestQueue.Request>()
        val runner = launch {
            try { queue.take(); awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    finishCleanup.await()
                    handoff = queue.retireRunner()
                }
            }
        }
        runCurrent(); runner.cancel(); cleanupStarted.await()
        assertThat(runner.isActive).isFalse()
        queue.add(listOf("/B"), StorageAccessMode.DIRECT)
        assertThat(queue.attachRunner()).isFalse()
        finishCleanup.complete(Unit); runner.join()
        assertThat(handoff).containsExactly(ContentIndexRequestQueue.Request("/B", StorageAccessMode.DIRECT))
        queue.add(listOf("/C"), StorageAccessMode.DIRECT)
        assertThat(queue.attachRunner()).isTrue()
        assertThat(queue.take()!!.root).isEqualTo("/C")
    }

    @Test(timeout = 90_000) fun selectionTimingForLargeNewProjectPlans() {
        for (count in listOf(1_000, 4_000, 16_000)) {
            val ops = buildList<PlannedOperation> {
                repeat(200) { project ->
                    val home = FileRef.Direct("/Documents/Project $project")
                    add(PlannedOperation.CreateDirectory(FileRef.Direct("/Documents"), "Project $project", "audit"))
                    repeat(3) { role -> add(PlannedOperation.CreateDirectory(home, "Role $role", "audit")) }
                }
                repeat(count) { n -> add(PlannedOperation.Move(FileRef.Direct("/Downloads/$n.txt"),
                    FileRef.Direct("/Documents/Project ${n % 200}/Role ${(n / 200) % 3}/$n.txt"), "audit")) }
            }
            val selected = PlanSelection.allSelected(ops)
            val dependencies = PlanSelection.dependencies(ops)
            val times = List(3) {
                val start = System.nanoTime()
                val changed = PlanSelection.setSelected(ops, selected, ops.lastIndex, false, dependencies)
                assertThat(ops.lastIndex in changed).isFalse()
                (System.nanoTime() - start) / 1_000_000
            }
            println("STABILIZED checkbox files=$count createDirs=800 milliseconds=$times")
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                handle(method.name, args ?: emptyArray())
            } as T
    }
}
