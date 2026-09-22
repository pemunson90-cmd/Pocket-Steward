package com.pocketsteward.app.scan

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileRecordDao
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.data.db.ScanCheckpoint
import com.pocketsteward.app.data.db.ScanCheckpointDao
import com.pocketsteward.app.data.db.ScanStatus
import com.pocketsteward.app.storage.FileEntry
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class FileScannerInterruptionTest {
    private val root = FileRef.Direct("/root")
    private val a = FileRef.Direct("/root/a.txt")
    private val sub = FileRef.Direct("/root/sub")
    private val b = FileRef.Direct("/root/sub/b.txt")
    private val stale = FileRef.Direct("/root/stale.txt")

    @Test
    fun pausePreservesLastGoodInventory_thenResumeContinuesQueueAndCleansStaleRows() = runTest {
        val records = FakeFileRecordDao()
        val checkpoints = FakeCheckpointDao()
        val gateway = TreeGateway(
            children = mapOf(
                root to listOf(
                    FileEntry(a, "a.txt", isDirectory = false, parentRef = root),
                    FileEntry(sub, "sub", isDirectory = true, parentRef = root),
                ),
                sub to listOf(
                    FileEntry(b, "b.txt", isDirectory = false, parentRef = sub),
                ),
            ),
        )

        // Existing inventory is deliberately retained during an interrupted
        // refresh. a.txt also carries expensive metadata that a Level-0 scan
        // must not erase.
        records.seed(
            file(a, parent = root, lastScannedAt = 1L).copy(
                classification = "project:a",
                classificationConfidence = 1.0f,
                width = 123,
            ),
            root.raw(),
        )
        records.seed(file(stale, parent = root, lastScannedAt = 1L), root.raw())

        val scanner = FileScanner(gateway, records, checkpoints)
        var interrupted = false
        try {
            scanner.scan(root) { progress ->
                if (!interrupted && progress.currentDirectoryName == root.raw()) {
                    interrupted = true
                    throw CancellationException("synthetic process stop")
                }
            }
            fail("The synthetic interruption should have cancelled the scan.")
        } catch (_: CancellationException) {
            // expected
        }

        val paused = checkpoints.get(root.raw())!!
        assertThat(paused.status).isEqualTo(ScanStatus.PAUSED)
        assertThat(paused.processedCount).isEqualTo(2)
        assertThat(FileRefCodec.decodeList(paused.pendingDirectoriesJson))
            .containsExactly(sub)

        // A cancelled refresh must not prune records it has not had a chance
        // to revisit.
        assertThat(records.getByStableRef(stale.raw())).isNotNull()
        assertThat(records.scopesFor(stale.raw())).contains(root.raw())

        val refreshedA = records.getByStableRef(a.raw())!!
        assertThat(refreshedA.classification).isEqualTo("project:a")
        assertThat(refreshedA.width).isEqualTo(123)

        // Resume uses the durable queue rather than restarting /root.
        scanner.scan(root)

        val completed = checkpoints.get(root.raw())!!
        assertThat(completed.status).isEqualTo(ScanStatus.COMPLETED)
        assertThat(completed.processedCount).isEqualTo(3)
        assertThat(FileRefCodec.decodeList(completed.pendingDirectoriesJson)).isEmpty()

        assertThat(gateway.listCount[root]).isEqualTo(1)
        assertThat(gateway.listCount[sub]).isEqualTo(1)

        assertThat(records.getAllUnderScopeRoot(root.raw()).map { it.stableRef })
            .containsExactly(root.raw(), a.raw(), sub.raw(), b.raw())

        // Only after the completed walk is a previously-known-but-unseen file
        // detached from this scope and, because no other scope owns it,
        // removed as an orphan.
        assertThat(records.getByStableRef(stale.raw())).isNull()

        // Upsert semantics prevent duplicate rows across pause/resume.
        assertThat(records.allRecords().map { it.stableRef }.distinct())
            .hasSize(records.allRecords().size)

        val finalA = records.getByStableRef(a.raw())!!
        assertThat(finalA.classification).isEqualTo("project:a")
        assertThat(finalA.width).isEqualTo(123)
    }

    private fun FileRef.raw(): String = (this as FileRef.Direct).absolutePath

    private fun file(
        ref: FileRef.Direct,
        parent: FileRef.Direct?,
        lastScannedAt: Long,
    ) = FileRecord(
        stableRef = ref.absolutePath,
        displayName = ref.absolutePath.substringAfterLast('/'),
        extension = ref.absolutePath.substringAfterLast('.', ""),
        mimeType = "text/plain",
        absolutePathOrUri = ref.absolutePath,
        parentRef = parent?.absolutePath,
        sizeBytes = if (ref == sub || ref == root) 0 else 10,
        createdAt = null,
        modifiedAt = 10,
        lastScannedAt = lastScannedAt,
        isDirectory = ref == sub || ref == root,
        isHidden = false,
    )

    private inner class TreeGateway(
        private val children: Map<FileRef, List<FileEntry>>,
    ) : StorageGateway {
        val listCount = mutableMapOf<FileRef, Int>()

        override suspend fun rootOf(scope: StorageScope): FileRef = root

        override suspend fun listChildren(directory: FileRef): List<FileEntry> {
            listCount[directory] = (listCount[directory] ?: 0) + 1
            return children[directory].orEmpty()
        }

        override suspend fun stat(ref: FileRef): FileMetadata {
            val direct = ref as FileRef.Direct
            val directory = ref == root || ref == sub
            return FileMetadata(
                ref = ref,
                displayName = direct.absolutePath.substringAfterLast('/'),
                extension = if (directory) "" else direct.absolutePath.substringAfterLast('.', ""),
                mimeType = if (directory) null else "text/plain",
                sizeBytes = if (directory) 0 else 10,
                createdAtEpochMs = null,
                modifiedAtEpochMs = 10,
                isDirectory = directory,
                isHidden = false,
            )
        }

        override suspend fun exists(ref: FileRef): Boolean =
            ref == root || ref == a || ref == sub || ref == b

        override suspend fun openRead(ref: FileRef): InputStream =
            ByteArrayInputStream(byteArrayOf(1))

        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult =
            error("not used")

        override suspend fun writeTextFile(
            parent: FileRef,
            name: String,
            content: String,
        ): MutationResult = error("not used")

        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun move(source: FileRef, destination: FileRef): MutationResult =
            error("not used")

        override suspend fun rename(source: FileRef, newName: String): MutationResult =
            error("not used")

        override suspend fun trashDestination(source: FileRef): FileRef =
            error("not used")

        override suspend fun trash(source: FileRef): MutationResult =
            error("not used")

        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult =
            error("not used")
    }

    private class FakeCheckpointDao : ScanCheckpointDao {
        private val values = mutableMapOf<String, ScanCheckpoint>()

        override suspend fun upsert(checkpoint: ScanCheckpoint) {
            values[checkpoint.scopeRootRef] = checkpoint
        }

        override suspend fun get(scopeRootRef: String): ScanCheckpoint? =
            values[scopeRootRef]

        override suspend fun clear(scopeRootRef: String) {
            values.remove(scopeRootRef)
        }
    }

    private class FakeFileRecordDao : FileRecordDao {
        private val records = linkedMapOf<String, FileRecord>()
        private val scopes = linkedSetOf<FileScope>()
        private var nextId = 1L

        fun seed(record: FileRecord, scopeRoot: String) {
            val stored = record.copy(id = nextId++)
            records[stored.stableRef] = stored
            scopes += FileScope(stored.stableRef, scopeRoot)
        }

        fun scopesFor(fileRef: String): Set<String> =
            scopes.filter { it.fileRef == fileRef }.mapTo(linkedSetOf()) { it.scopeRoot }

        fun allRecords(): List<FileRecord> = records.values.toList()

        override suspend fun insert(record: FileRecord): Long {
            if (record.stableRef in records) return -1
            val id = if (record.id == 0L) nextId++ else record.id
            records[record.stableRef] = record.copy(id = id)
            return id
        }

        override suspend fun update(record: FileRecord): Int {
            if (record.stableRef !in records) return 0
            records[record.stableRef] = record
            return 1
        }

        override suspend fun insertScopeTag(scope: FileScope) {
            scopes += scope
        }

        override suspend fun insertScopeTags(scopes: List<FileScope>) {
            this.scopes += scopes
        }

        override suspend fun getById(id: Long): FileRecord? =
            records.values.firstOrNull { it.id == id }

        override fun observeChildren(parentRef: String): Flow<List<FileRecord>> =
            flowOf(records.values.filter { it.parentRef == parentRef })

        override suspend fun countChildren(parentRef: String): Int =
            records.values.count { it.parentRef == parentRef }

        override suspend fun getFilesUnderScopeRoot(scopeRootRef: String): List<FileRecord> =
            scoped(scopeRootRef).filterNot { it.isDirectory }

        override suspend fun getLargestFiles(scopeRootRef: String, limit: Int): List<FileRecord> =
            getFilesUnderScopeRoot(scopeRootRef).sortedByDescending { it.sizeBytes }.take(limit)

        override suspend fun getFilesOlderThan(
            scopeRootRef: String,
            cutoffMillis: Long,
        ): List<FileRecord> =
            getFilesUnderScopeRoot(scopeRootRef)
                .filter { it.modifiedAt != null && it.modifiedAt < cutoffMillis }
                .sortedBy { it.modifiedAt }

        override suspend fun getAllUnderScopeRoot(scopeRootRef: String): List<FileRecord> =
            scoped(scopeRootRef)

        override suspend fun getByStableRef(stableRef: String): FileRecord? =
            records[stableRef]

        override suspend fun deleteByStableRef(stableRef: String) {
            records.remove(stableRef)
            scopes.removeAll { it.fileRef == stableRef }
        }

        override suspend fun getKnownScopeRoots(): List<String> =
            scopes.map { it.scopeRoot }.distinct()

        override suspend fun removeScopeTags(scopeRootRef: String) {
            scopes.removeAll { it.scopeRoot == scopeRootRef }
        }

        override suspend fun removeStaleScopeTags(
            scopeRootRef: String,
            scanStartedAt: Long,
        ) {
            val staleRefs = records.values
                .filter { it.lastScannedAt < scanStartedAt }
                .mapTo(hashSetOf()) { it.stableRef }
            scopes.removeAll { it.scopeRoot == scopeRootRef && it.fileRef in staleRefs }
        }

        override suspend fun deleteOrphanedFiles() {
            val owned = scopes.mapTo(hashSetOf()) { it.fileRef }
            records.keys.removeAll { it !in owned }
        }

        override suspend fun clearAll() {
            records.clear()
            scopes.clear()
        }

        private fun scoped(scopeRootRef: String): List<FileRecord> {
            val refs = scopes
                .filter { it.scopeRoot == scopeRootRef }
                .mapTo(hashSetOf()) { it.fileRef }
            return records.values.filter { it.stableRef in refs }
        }
    }
}
