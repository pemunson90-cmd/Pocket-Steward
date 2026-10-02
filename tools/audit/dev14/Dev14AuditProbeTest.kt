package com.pocketsteward.app.audit

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
import org.junit.Test
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy

/** Temporary audit probes: assertions document observed dev14 defects, not desired behavior. */
class Dev14AuditProbeTest {
    @Test fun cancellingFilingLeavesBusyStateSet() {
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
        assertThat(vm.busy.value).isEqualTo(working)
        println("AUDIT cancel: actual job cancelled and event Idle; busy remains Planning")
    }

    @Test fun renamePrimitiveOverwritesDestinationCreatedAfterPrecheck() {
        val directory = java.nio.file.Files.createTempDirectory("ps-audit-rename").toFile()
        try {
            val source = java.io.File(directory, "source.txt").apply { writeText("approved source") }
            val destination = java.io.File(directory, "destination.txt")
            assertThat(destination.exists()).isFalse() // Gateway precheck.
            destination.writeText("external file") // Controlled external-writer interleaving.
            assertThat(source.renameTo(destination)).isTrue() // The primitive called by the real gateway.
            assertThat(destination.readText()).isEqualTo("approved source")
            println("AUDIT rename primitive: external destination was replaced after absence precheck (host filesystem)")
        } finally { directory.deleteRecursively() }
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

    @Test fun resumeSkipsNewDocumentBeforeSavedCursorButClaimsComplete() = runTest {
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
        assertThat(f.documents.keys).containsExactly(b.stableRef, c.stableRef)
        println("AUDIT resume: COMPLETED 3/3, a.txt was never indexed")
    }

    @Test fun concurrentFilingWriterCanReplaceCompletedFullContentWithTruncatedContent() = runTest {
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
        f.repository.refreshRootResumable(listOf(ContentIndexCandidate(r, "/Downloads")), "/Downloads")
        assertThat(f.documents[r.stableRef]!!.extractionProfile).isEqualTo("FULL")
        assertThat(f.state!!.completed).isTrue()
        releaseExtraction.complete(Unit); pending.await()
        assertThat(f.documents[r.stableRef]!!.extractionProfile).isEqualTo("FILING")
        assertThat(f.documents[r.stableRef]!!.coverageComplete).isFalse()
        assertThat(f.state!!.completed).isTrue()
        println("AUDIT concurrent cache: full job remains COMPLETED, content downgraded to FILING")
    }

    @Test fun identicalFilesCanBeMissedWhenProviderReturnsShortReads() = runTest {
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
        assertThat(DuplicateDetector(gateway).findDuplicates(listOf(a, b))).isEmpty()
        println("AUDIT duplicates: identical bytes, legal short-read stream => no duplicate group")
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
            val times = List(3) {
                val start = System.nanoTime()
                val changed = PlanSelection.setSelected(ops, selected, ops.lastIndex, false)
                assertThat(ops.lastIndex in changed).isFalse()
                (System.nanoTime() - start) / 1_000_000
            }
            println("AUDIT checkbox files=$count createDirs=800 milliseconds=$times")
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                handle(method.name, args ?: emptyArray())
            } as T
    }
}
