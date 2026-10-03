package com.pocketsteward.app.storage

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.pocketsteward.app.data.db.*
import com.pocketsteward.app.executor.*
import com.pocketsteward.app.filing.*
import com.pocketsteward.app.plan.*
import com.pocketsteward.app.report.*
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.plan.SourcePreconditions
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [ModernDocumentsQueryBridge::class])
class SafNativeFolderTest {
    private lateinit var app: Application
    private lateinit var provider: OpaqueDocumentsProvider
    private lateinit var gateway: SafStorageGateway
    private fun ref(id: String, grant: String = "root") = FileRef.Saf(DocumentsContract.buildDocumentUriUsingTree(
        DocumentsContract.buildTreeDocumentUri(OpaqueDocumentsProvider.AUTHORITY, grant), id).toString())
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        val info = ProviderInfo().apply {
            authority = OpaqueDocumentsProvider.AUTHORITY; name = OpaqueDocumentsProvider::class.java.name
            packageName = app.packageName; exported = true; grantUriPermissions = true
            applicationInfo = app.applicationInfo
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(DocumentsContract.PROVIDER_INTERFACE), ResolveInfo().apply { providerInfo = info })
        provider = OpaqueDocumentsProvider()
        provider.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal(info.authority, provider)
        gateway = SafStorageGateway(app)
        provider.seed("inbox", "root", "Download")
        provider.seed("homes", "root", "Documents")
        provider.seed("bundle", "inbox", "Lilith")
        provider.seed("notes", "bundle", "Notes")
        provider.seed("text", "notes", "scene.txt", "Chapter one")
    }
    @Test fun boundChildScopeDoesNotExpandToTheTreeGrantRoot() = runTest {
        assertThat(DocumentsContract.isDocumentUri(app, Uri.parse(ref("inbox").documentUri))).isTrue()
        assertThat(gateway.rootOf(StorageScope.Tree(ref("inbox"), "Inbox"))).isEqualTo(ref("inbox"))
        assertThat(gateway.listChildren(ref("inbox")).map { it.displayName }).containsExactly("Lilith")
    }
    @Test fun nativeMoveAndUndoPreserveAnOpaqueFolderAndItsContents() = runTest {
        val before = SourcePreconditions.capture(gateway, ref("bundle"))
        val original = requireNotNull(gateway.locationOf(ref("bundle")))
        val moved = gateway.move(ref("bundle"), ref("homes").child("Lilith"))
        assertThat(moved).isInstanceOf(MutationResult.Success::class.java)
        assertThat((moved as MutationResult.Success).resultRef).isEqualTo(ref("bundle"))
        assertThat(gateway.exists(ref("bundle"))).isTrue() // opaque identity survives; location does not
        assertThat(gateway.exists(original)).isFalse()
        assertThat(gateway.exists(ref("homes").child("Lilith"))).isTrue()
        assertThat(gateway.openRead(ref("text")).bufferedReader().use { it.readText() }).isEqualTo("Chapter one")
        val after = SourcePreconditions.capture(gateway, ref("bundle"))
        assertThat(after.directoryDigest).isEqualTo(before.directoryDigest)
        assertThat(provider.moves).isEqualTo(1)
        assertThat(provider.deletes).isEqualTo(0)
        assertThat(gateway.move(ref("bundle"), original)).isInstanceOf(MutationResult.Success::class.java)
        assertThat(gateway.exists(original)).isTrue()
        assertThat(gateway.exists(ref("homes").child("Lilith"))).isFalse()
        assertThat(provider.moves).isEqualTo(2)
    }
    @Test fun unsupportedAndCompoundMovesLeaveTheOriginalIntact() = runTest {
        provider.nativeMoves = false
        val unsupported = gateway.move(ref("bundle"), ref("homes").child("Lilith")) as MutationResult.Failure
        assertThat(unsupported.reason).contains("does not support")
        provider.nativeMoves = true
        val compound = gateway.move(ref("bundle"), ref("homes").child("New name")) as MutationResult.Failure
        assertThat(compound.reason).contains("separately reviewed")
        assertThat(provider.moves).isEqualTo(0)
        assertThat(gateway.exists(ref("inbox").child("Lilith"))).isTrue()
        assertThat(provider.deletes).isEqualTo(0)
    }
    @Test fun collisionsDescendantsAndProtectionPreventNativeMutation() = runTest {
        provider.seed("other", "homes", "lilith")
        assertThat(gateway.move(ref("bundle"), ref("homes").child("Lilith"))).isInstanceOf(MutationResult.Failure::class.java)
        assertThat(gateway.move(ref("bundle"), ref("notes").child("Lilith"))).isInstanceOf(MutationResult.Failure::class.java)
        provider.nodes.remove("other")
        provider.seed("marker", "notes", DirectProtection.MARKER, "")
        val blocked = gateway.move(ref("bundle"), ref("homes").child("Lilith")) as MutationResult.Failure
        assertThat(blocked.reason).contains("Protected")
        assertThat(provider.moves).isEqualTo(0)
        assertThat(provider.deletes).isEqualTo(0)
    }
    @Test fun incompleteProviderListingsCannotCertifyOrDeleteAnEmptyDirectory() = runTest {
        provider.seed("empty", "inbox", "Empty")
        for (error in listOf(false, true)) {
            provider.loading = !error; provider.listingError = error
            try { SourcePreconditions.capture(gateway, ref("empty")); throw AssertionError("Incomplete listing accepted") }
            catch (expected: IllegalStateException) { assertThat(expected.message).contains(if (error) "failed" else "loading") }
            try { gateway.removeEmptyDirectory(ref("empty")); throw AssertionError("Incomplete listing accepted") }
            catch (expected: IllegalStateException) { assertThat(expected.message).contains(if (error) "failed" else "loading") }
            assertThat(provider.deletes).isEqualTo(0)
        }
    }
    @Test fun nativeRenameIsOneStepAndDoesNotOverwrite() = runTest {
        assertThat(gateway.rename(ref("bundle"), "Renamed")).isInstanceOf(MutationResult.Success::class.java)
        assertThat(gateway.exists(ref("inbox").child("Lilith"))).isFalse()
        assertThat(gateway.exists(ref("inbox").child("Renamed"))).isTrue()
        assertThat(provider.renames).isEqualTo(1)
        provider.seed("other", "inbox", "Occupied")
        assertThat(gateway.rename(ref("bundle"), "Occupied")).isInstanceOf(MutationResult.Failure::class.java)
        assertThat(provider.renames).isEqualTo(1)
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            com.pocketsteward.app.scan.FileScanner(gateway, db.fileRecordDao(), db.scanCheckpointDao()).scan(ref("root"))
            block(db)
        } finally { db.close() }
    }
    private fun executor(db: AppDatabase) = PlanExecutor(gateway, db.fileRecordDao(), db.taskRunDao(), db.mutationRecordDao())
    private fun undo(db: AppDatabase) = UndoExecutor(db.fileRecordDao(), db.taskRunDao(), db.mutationRecordDao(), { gateway })
    private fun recovery(db: AppDatabase) = MutationRecovery(db.mutationRecordDao(), db.taskRunDao(), gatewayFor = { gateway })
    private suspend fun reviewedPlan(): AgentPlan {
        val source = ref("bundle")
        val destination = ref("homes").child("Lilith")
        val operation = PlannedOperation.Move(source, destination, "Keep this reviewed bundle together")
        val baseline = SourcePreconditions.capture(gateway, source)
        val item = FilingTaskItem(FileRefJournalCodec.encode(source), "Lilith", true, FilingOutcome.DESTINATION,
            "Reviewed", 0, FileRefJournalCodec.encode(destination), baseline.location)
        return AgentPlan("File intact bundle", listOf(operation), mapOf(source.rawValue() to baseline),
            FilingTaskInventory(listOf(item), null, 2))
    }
    @Test fun executorJournalReconciliationAndUndoUseLocationsRatherThanRetainedIds() = runTest {
        withDatabase { db ->
            val summary = executor(db).execute(reviewedPlan(), ref("root").rawValue(), StorageAccessMode.SAF)
            assertThat(summary.failed).isEqualTo(0)
            assertThat(summary.filesMoved).isEqualTo(1)
            val record = db.mutationRecordDao().getForTaskRun(summary.taskRunId).single()
            assertThat(FileRefJournalCodec.decode(requireNotNull(record.destinationAfter))).isEqualTo(ref("homes").child("Lilith"))
            assertThat(StorageDigest.isFolderProof(requireNotNull(record.sourceFingerprint))).isTrue()
            val durable = requireNotNull(DurablePlanCodec.decodeOrNull(requireNotNull(db.taskRunDao().getById(summary.taskRunId)).planJson))
            assertThat(FilingReconciler.check(durable, listOf(record), gateway)!!.locations.single().state).isEqualTo(FilingLocationState.FILED)
            assertThat(undo(db).undoSingleMutation(record.id)).isInstanceOf(MutationResult.Success::class.java)
            assertThat(gateway.exists(ref("inbox").child("Lilith"))).isTrue()
            val undone = db.mutationRecordDao().getById(record.id)!!
            assertThat(FilingReconciler.check(durable, listOf(undone), gateway)!!.locations.single().state).isEqualTo(FilingLocationState.UNDONE)
            assertThat(provider.moves).isEqualTo(2)
            assertThat(provider.deletes).isEqualTo(0)
        }
    }
    @Test fun changedOpaqueParentAfterReviewCannotBeApprovedAsTheOldLocation() = runTest {
        withDatabase { db ->
            val plan = reviewedPlan()
            assertThat(gateway.move(ref("bundle"), ref("homes").child("Lilith"))).isInstanceOf(MutationResult.Success::class.java)
            try {
                executor(db).enqueueApproved(plan, ref("root").rawValue(), StorageAccessMode.SAF)
                throw AssertionError("Externally relocated source was accepted")
            } catch (expected: IllegalStateException) { assertThat(expected.message).contains("changed after") }
            assertThat(db.taskRunDao().getRunning()).isEmpty()
            assertThat(provider.moves).isEqualTo(1)
        }
    }
    private suspend fun pendingMove(db: AppDatabase): MutationRecord {
        val plan = reviewedPlan()
        val task = executor(db).enqueueApproved(plan, ref("root").rawValue(), StorageAccessMode.SAF)
        val record = MutationRecord(taskRunId = task, sequence = 0, operationType = MutationOperationType.MOVE,
            sourceBefore = requireNotNull(plan.reviewedSources.values.single().location),
            destinationAfter = FileRefJournalCodec.encode(ref("homes").child("Lilith")),
            sourceFingerprint = StorageDigest.proof(gateway, ref("bundle")), status = MutationStatus.PENDING,
            executedAt = null, undoState = UndoState.NOT_AVAILABLE, undoAttemptedAt = null, error = null, undoError = null)
        val id = db.mutationRecordDao().insert(record)
        assertThat(gateway.move(ref("bundle"), ref("homes").child("Lilith"))).isInstanceOf(MutationResult.Success::class.java)
        return record.copy(id = id)
    }
    @Test fun interruptedNativeMoveRecoversOnlyTheMatchingFolderWitness() = runTest {
        withDatabase { db ->
            val record = pendingMove(db)
            recovery(db).recoverAll()
            assertThat(db.mutationRecordDao().getById(record.id)!!.status).isEqualTo(MutationStatus.COMMITTED)
            assertThat(db.taskRunDao().getById(record.taskRunId)!!.status).isEqualTo(TaskRunStatus.COMPLETED)
        }
    }
    @Test fun interruptedNativeMoveWithChangedContentsRequiresReview() = runTest {
        withDatabase { db ->
            val record = pendingMove(db)
            provider.nodes.getValue("text").name = "changed.txt"
            recovery(db).recoverAll()
            assertThat(db.mutationRecordDao().getById(record.id)!!.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
            assertThat(db.mutationRecordDao().getById(record.id)!!.undoState).isEqualTo(UndoState.BLOCKED)
            assertThat(provider.moves).isEqualTo(1)
        }
    }
    @Test fun interruptedUndoRecognizesReturnedLocationDespiteRetainedDocumentId() = runTest {
        withDatabase { db ->
            val summary = executor(db).execute(reviewedPlan(), ref("root").rawValue(), StorageAccessMode.SAF)
            val record = db.mutationRecordDao().getForTaskRun(summary.taskRunId).single()
            db.mutationRecordDao().update(record.copy(undoState = UndoState.PENDING))
            assertThat(gateway.move(ref("bundle"), FileRefJournalCodec.decode(record.sourceBefore))).isInstanceOf(MutationResult.Success::class.java)
            recovery(db).recoverAll()
            assertThat(db.mutationRecordDao().getById(record.id)!!.status).isEqualTo(MutationStatus.UNDONE)
            assertThat(db.mutationRecordDao().getById(record.id)!!.undoState).isEqualTo(UndoState.UNDONE)
        }
    }
    @Test fun changedFolderIsBlockedBeforeAnInverseMutation() = runTest {
        withDatabase { db ->
            val summary = executor(db).execute(reviewedPlan(), ref("root").rawValue(), StorageAccessMode.SAF)
            val record = db.mutationRecordDao().getForTaskRun(summary.taskRunId).single()
            provider.nodes.getValue("text").name = "changed.txt"
            val blocked = undo(db).undoSingleMutation(record.id) as MutationResult.Failure
            assertThat(blocked.reason).contains("folder changed")
            assertThat(provider.moves).isEqualTo(1)
            assertThat(gateway.exists(ref("homes").child("Lilith"))).isTrue()
        }
    }
    @Test fun sourceLocationChecksDoNotListEverySiblingForEveryFile() = runTest {
        repeat(1000) { provider.seed("file-$it", "inbox", "file-$it.txt", "text") }
        val before = provider.childQueries
        repeat(1000) { assertThat(requireNotNull(gateway.locationOf(ref("file-$it"))).parent).isEqualTo(ref("inbox")) }
        assertThat(provider.childQueries).isEqualTo(before)
    }
    @Test fun filingAdapterLetsAnIntactFolderBecomeItsNewHomeInOneNativeMove() = runTest {
        val artifact = FilingArtifact(ref("bundle").rawValue(), "Lilith", "", 0, modifiedAt = 1000, parentRef = ref("inbox").rawValue(), isDirectory = true)
        val home = ProjectHomeCandidate("Lilith", "${ref("homes").rawValue()}/Lilith")
        val decision = FilingDecision(artifact, "Lilith", home, null, home.path, FilingConfidence.PROBABLE,
            listOf(FilingEvidence(FilingEvidenceKind.FOLDER_CONTENT, "One owner; folder stays intact", 60)), createsProjectHome = true)
        val plan = InboxFilingSafPlanAdapter.build(InboxFilingResult(listOf(decision)), ref("inbox"), emptyMap(), "Inbox", newHomeRoot = ref("homes"))
        assertThat(plan.operations).hasSize(1)
        val move = plan.operations.single() as PlannedOperation.Move
        assertThat(move.destination).isEqualTo(ref("homes").child("Lilith"))
        assertThat(plan.presentation.groups.single().items.single().isDirectory).isTrue()
        assertThat(plan.defaultSelectedSourceRefs).isEmpty()
        assertThat(gateway.move(move.source, move.destination)).isInstanceOf(MutationResult.Success::class.java)
        assertThat(gateway.listChildren(ref("bundle")).map { it.displayName }).containsExactly("Notes")
        assertThat(provider.moves).isEqualTo(1)
    }
    @Test fun selectedTreeReleaseDiscoveryUsesDisplayNamesAndConcreteOpaqueChildren() = runTest {
        provider.nodes.getValue("bundle").parent = "homes"
        provider.seed("versions-id", "bundle", "Versions")
        provider.seed("release-id", "versions-id", "v1.0-dev2")
        provider.seed("release-notes-id", "release-id", "notes")
        val artifact = FilingArtifact(ref("text").rawValue(), "Lilith-v1.0-dev2-notes.txt", "txt", 11, modifiedAt = 1000, parentRef = ref("notes").rawValue())
        val home = ProjectHomeCandidate("Lilith", ref("bundle").rawValue(), hierarchy = com.pocketsteward.app.saved.ProjectHierarchyStrategy.PROJECT_ROLES)
        val decision = FilingDecision(artifact, "Lilith", home, "1.0-dev2", "${home.path}/Versions/1.0-dev2/Notes", FilingConfidence.STRONG, emptyList())
        val result = InboxFilingResult(listOf(decision))
        val layout = FilingReleaseDiscovery.discover(result, gateway, mapOf(home.path to ref("bundle")))
        assertThat(layout.unavailableHomes).isEmpty()
        assertThat(layout.directories).contains("${home.path}/Versions/v1.0-dev2/notes")
        val reconciled = FilingReleaseConvention.reconcile(result, layout.directories)
        assertThat(reconciled.decisions.single().destinationDirectory).isEqualTo("${home.path}/Versions/v1.0-dev2/notes")
        val plan = InboxFilingSafPlanAdapter.build(reconciled, ref("root"), mapOf(home.path to ref("bundle")), "Storage")
        val destination = plan.operations.filterIsInstance<PlannedOperation.Move>().single().destination
        assertThat(gateway.exists(destination.knownParentOrNull()!!)).isTrue()
        assertThat((destination as FileRef.Child).parent).isEqualTo(ref("bundle").child("Versions").child("v1.0-dev2").child("notes"))
    }
    @Test fun inaccessibleOriginalLocationNeverBecomesProofThatTheSourceIsGone() = runTest {
        withDatabase { db ->
            val record = pendingMove(db)
            provider.unavailableParents += "inbox"
            recovery(db).recoverAll()
            assertThat(db.mutationRecordDao().getById(record.id)!!.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
            assertThat(db.mutationRecordDao().getById(record.id)!!.error).contains("access could not verify")
            assertThat(provider.moves).isEqualTo(1)
        }
    }
    @Test fun inaccessibleOriginalLocationBlocksUndoWithoutOverwritingOrMoving() = runTest {
        withDatabase { db ->
            val summary = executor(db).execute(reviewedPlan(), ref("root").rawValue(), StorageAccessMode.SAF)
            val record = db.mutationRecordDao().getForTaskRun(summary.taskRunId).single()
            provider.unavailableParents += "inbox"
            assertThat(undo(db).undoSingleMutation(record.id)).isInstanceOf(MutationResult.Failure::class.java)
            assertThat(db.mutationRecordDao().getById(record.id)!!.undoState).isEqualTo(UndoState.BLOCKED)
            assertThat(provider.moves).isEqualTo(1)
        }
    }
    @Test fun folderTrashAndRestoreUseOneNativeMoveEachAndRetainTheFolderName() = runTest {
        withDatabase { db ->
            val source = ref("bundle")
            val plan = AgentPlan("Trash this intact bundle", listOf(PlannedOperation.Trash(source, "Reviewed folder Trash")),
                mapOf(source.rawValue() to SourcePreconditions.capture(gateway, source)))
            val summary = executor(db).execute(plan, ref("root").rawValue(), StorageAccessMode.SAF)
            assertThat(summary.failed).isEqualTo(0)
            val record = db.mutationRecordDao().getForTaskRun(summary.taskRunId).single()
            val destination = FileRefJournalCodec.decode(record.destinationAfter!!)
            assertThat(gateway.stat(destination).displayName).isEqualTo("Lilith")
            assertThat(gateway.exists(ref("inbox").child("Lilith"))).isFalse()
            assertThat(undo(db).undoSingleMutation(record.id)).isInstanceOf(MutationResult.Success::class.java)
            assertThat(gateway.openRead(ref("text")).bufferedReader().use { it.readText() }).isEqualTo("Chapter one")
            assertThat(provider.moves).isEqualTo(2)
            assertThat(provider.deletes).isEqualTo(0)
        }
    }
    @Test fun ambiguousProviderDeleteNeverRemovesTheVerifiedSurvivingCopy() = runTest {
        provider.failDeleteAfterRemoving += "text"
        val destination = ref("homes").child("scene.txt")
        val moved = gateway.move(ref("text"), destination) as MutationResult.Failure
        assertThat(moved.reason).contains("verified destination copy was retained")
        assertThat(gateway.openRead(destination).bufferedReader().use { it.readText() }).isEqualTo("Chapter one")
        assertThat(provider.deletes).isEqualTo(1)
    }
}

/** Real DocumentsContract calls, opaque retained IDs and parent paths; no gateway mock. */
class OpaqueDocumentsProvider : DocumentsProvider() {
    companion object { const val AUTHORITY = "com.pocketsteward.host.opaque" }
    data class Node(val id: String, var parent: String?, var name: String, val text: String?)
    val nodes = linkedMapOf("root" to Node("root", null, "Storage", null))
    var nativeMoves = true
    var loading = false
    var listingError = false
    var moves = 0; var renames = 0; var deletes = 0
    var childQueries = 0
    val unavailableParents = hashSetOf<String>()
    val failDeleteAfterRemoving = hashSetOf<String>()
    private val files = hashMapOf<String, File>()
    private var created = 0
    fun seed(id: String, parent: String, name: String, text: String? = null) { nodes[id] = Node(id, parent, name, text) }
    override fun onCreate() = true
    private val defaultColumns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS)
    private fun cursor(projection: Array<out String>?, rows: List<Node>): Cursor {
        val columns = projection ?: defaultColumns
        return object : MatrixCursor(columns) {
            override fun getExtras() = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, loading)
                if (listingError) putString(DocumentsContract.EXTRA_ERROR, "Simulated unavailable listing")
            }
        }.apply { rows.forEach { node -> addRow(columns.map<String, Any?> { col -> when(col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (node.text == null) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
            DocumentsContract.Document.COLUMN_SIZE -> files[node.id]?.length() ?: node.text?.toByteArray()?.size?.toLong() ?: 0L
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1000L
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                if (nativeMoves) DocumentsContract.Document.FLAG_SUPPORTS_MOVE else 0
            else -> null
        }}.toTypedArray()) } }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { col -> when(col) {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID -> "root"
            else -> null
        }}.toTypedArray()) }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection, listOf(nodes.getValue(documentId)))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries++
        check(parentDocumentId !in unavailableParents) { "Simulated unavailable original folder access" }
        return cursor(projection, nodes.values.filter { it.parent == parentDocumentId })
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(nodes.values.none { it.parent == parentDocumentId && it.name.equals(displayName, true) })
        val id = "created-${++created}"
        seed(id, parentDocumentId, displayName, if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) null else "")
        return id
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = path(documentId).dropLast(1).contains(parentDocumentId)
    private fun path(id: String): List<String> {
        val route = mutableListOf<String>(); var current: String? = id
        while (current != null) { check(current !in route); route += current; current = nodes.getValue(current).parent }
        return route.reversed()
    }
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String): DocumentsContract.Path = DocumentsContract.Path("root", path(childDocumentId))
    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        check(nativeMoves); val node = nodes.getValue(sourceDocumentId); check(node.parent == sourceParentDocumentId)
        check(nodes.values.none { it.parent == targetParentDocumentId && it.name.equals(node.name, true) })
        node.parent = targetParentDocumentId; moves++; return node.id
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val node = nodes.getValue(documentId)
        check(nodes.values.none { it.id != node.id && it.parent == node.parent && it.name.equals(displayName, true) })
        node.name = displayName; renames++; return node.id
    }
    override fun deleteDocument(documentId: String) {
        deletes++; nodes.remove(documentId)
        if (documentId in failDeleteAfterRemoving) throw java.io.IOException("Provider interrupted after deletion")
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = nodes.getValue(documentId); check(node.text != null)
        val file = files.getOrPut(node.id) { File(requireNotNull(context).cacheDir, "opaque-${node.id}").apply { writeText(node.text) } }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
}

/** Model Android's legacy-to-bundle query dispatch, missing from this Robolectric shadow. */
@Implements(android.content.ContentResolver::class)
class ModernDocumentsQueryBridge : ShadowContentResolver() {
    @Implementation
    protected override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? =
        getProvider(uri)?.query(uri, projection, Bundle.EMPTY, null)
}
