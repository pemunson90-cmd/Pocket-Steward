package com.pocketsteward.app.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.storage.*
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class ProjectKnowledgeDiscoveryTest {
    @Test fun pagedRoomDiscoveryCoversMoreThanTwoHundredHomesAndCustomLayoutsInAdditionalIndexedRoots() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "discovery-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val homes = List(405) { n -> File(root, "Projects/Project%05d".format(n)).apply { mkdirs() } }
            val custom = File(root, "Writing Projects/Lilith").apply { mkdirs() }
            val customRole = File(custom, "WRITING/manuscript").apply { mkdirs() }
            val indexed = homes.flatMap { home -> listOf(home, File(home, "Notes").apply { mkdirs() }) } + listOf(custom, customRole.parentFile, customRole)
            val records = indexed.map { file -> FileRecord(stableRef = file.absolutePath, displayName = file.name, extension = "", mimeType = null,
                absolutePathOrUri = file.absolutePath, parentRef = file.parent, sizeBytes = 0, createdAt = null, modifiedAt = 1,
                lastScannedAt = 1, isDirectory = true, isHidden = false) }
            db.fileRecordDao().upsertAll(records)
            db.fileRecordDao().insertScopeTags(records.map { record -> FileScope(record.stableRef,
                if (record.stableRef.startsWith(File(root, "Projects").absolutePath + "/")) File(root, "Projects").absolutePath else File(root, "Writing Projects").absolutePath) })
            val checkpoints = ProjectDiscoveryStore(File(root, "private-pages"))
            val knowledge = ProjectKnowledge(db.fileRecordDao(), ReadOnlyGateway(), checkpoints)
            val found = knowledge.discover(FileRef.Direct(root.absolutePath), emptyList(), listOf(mapOf("Manuscript" to "Writing/manuscript")))
            assertThat(found.homes.size).isEqualTo(406)
            assertThat(found.limited).isFalse()
            assertThat(found.homes.first { it.path == custom.absolutePath }.roleFolders).containsExactly("Manuscript", "WRITING/manuscript")
            // Reopen the page journal; cached index observations are still verified live.
            File(homes.last(), "Notes").delete()
            val refreshed = ProjectKnowledge(db.fileRecordDao(), ReadOnlyGateway(), ProjectDiscoveryStore(File(root, "private-pages")))
                .discover(FileRef.Direct(root.absolutePath), emptyList(), listOf(mapOf("Manuscript" to "Writing/manuscript")))
            assertThat(refreshed.homes.size).isEqualTo(405)
            assertThat(refreshed.homes.map { it.path }).doesNotContain(homes.last().absolutePath)
        } finally { db.close(); root.deleteRecursively() }
    }
    private class ReadOnlyGateway : StorageGateway {
        override suspend fun stat(ref: FileRef): FileMetadata {
            val file = File((ref as FileRef.Direct).absolutePath)
            require(file.exists())
            return FileMetadata(ref, file.name, "", null, 0, null, 1, file.isDirectory, false)
        }
        override suspend fun exists(ref: FileRef) = File((ref as FileRef.Direct).absolutePath).exists()
        override suspend fun rootOf(scope: StorageScope): FileRef = error("No authority")
        override suspend fun listChildren(directory: FileRef): List<FileEntry> = error("Discovery verifies bounded role paths")
        override suspend fun openRead(ref: FileRef): InputStream = error("No content reads")
        override suspend fun createDirectory(parent: FileRef, name: String): MutationResult = error("Read-only")
        override suspend fun writeTextFile(parent: FileRef, name: String, content: String): MutationResult = error("Read-only")
        override suspend fun copy(source: FileRef, destination: FileRef): MutationResult = error("Read-only")
        override suspend fun move(source: FileRef, destination: FileRef): MutationResult = error("Read-only")
        override suspend fun rename(source: FileRef, newName: String): MutationResult = error("Read-only")
        override suspend fun trashDestination(source: FileRef): FileRef = error("Read-only")
        override suspend fun trash(source: FileRef): MutationResult = error("Read-only")
        override suspend fun removeEmptyDirectory(ref: FileRef): MutationResult = error("Read-only")
    }
}
