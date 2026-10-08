package com.pocketsteward.app.executor

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.storage.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MovedFolderIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun directMoveAndInverseReplaceAllDescendantPathsAndOverlappingMemberships() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val root = temporary.newFolder("storage")
            val downloads = File(root, "Download").apply { mkdirs() }
            val documents = File(root, "Documents").apply { mkdirs() }
            val source = File(downloads, "Lilith").apply { mkdirs() }
            val notes = File(source, "Notes").apply { mkdirs() }
            val unicode = listOf("a", "\uE000", "\uD800\uDC00", "\uD83D\uDC00")
            repeat(1_001) { File(notes, "${unicode[it % unicode.size]}-$it.txt").writeText("Scene $it") }
            val gateway = DirectStorageGateway(app)
            val scanner = FileScanner(gateway, db.fileRecordDao(), db.scanCheckpointDao())
            for (scope in listOf(root, downloads, documents, notes)) scanner.scan(FileRef.Direct(scope.absolutePath))
            val dao = db.fileRecordDao()
            val originalText = File(notes, "a-0.txt")
            dao.updateSha256(originalText.absolutePath, "old-derived-hash")
            val destination = File(documents, source.name)
            Files.move(source.toPath(), destination.toPath())
            val index = MovedFolderIndex(dao, gateway)
            index.replace(FileRef.Direct(source.absolutePath), FileRef.Direct(destination.absolutePath), downloads.absolutePath)
            assertThat(dao.getFilesUnderScopeRoot(downloads.absolutePath)).isEmpty()
            assertThat(dao.getFilesUnderScopeRoot(notes.absolutePath)).isEmpty()
            val moved = dao.getFilesUnderScopeRoot(documents.absolutePath)
            assertThat(moved).hasSize(1_001)
            assertThat(moved.all { it.stableRef.startsWith(destination.absolutePath + "/Notes/") }).isTrue()
            assertThat(dao.getByStableRef(originalText.absolutePath)).isNull()
            assertThat(dao.getByStableRef(File(destination, "Notes/a-0.txt").absolutePath)!!.sha256).isNull()
            assertThat(dao.getFilesUnderScopeRoot(root.absolutePath)).hasSize(1_001)
            assertThat(File(destination, "Notes/a-0.txt").readText()).isEqualTo("Scene 0")
            Files.move(destination.toPath(), source.toPath())
            index.replace(FileRef.Direct(destination.absolutePath), FileRef.Direct(source.absolutePath), downloads.absolutePath)
            assertThat(dao.getFilesUnderScopeRoot(documents.absolutePath)).isEmpty()
            assertThat(dao.getFilesUnderScopeRoot(downloads.absolutePath)).hasSize(1_001)
            assertThat(dao.getFilesUnderScopeRoot(notes.absolutePath)).hasSize(1_001)
            assertThat(dao.getByStableRef(File(destination, "Notes/a-0.txt").absolutePath)).isNull()
            assertThat(originalText.readText()).isEqualTo("Scene 0")
        } finally { db.close() }
    }

    @Test fun unavailableDescendantListingCannotPartiallyDeleteTheOldIndex() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val root = temporary.newFolder("storage")
            val source = File(root, "Source").apply { mkdirs() }
            val notes = File(source, "Notes").apply { mkdirs() }
            val text = File(notes, "scene.txt").apply { writeText("Keep this scene") }
            val gateway = DirectStorageGateway(app)
            FileScanner(gateway, db.fileRecordDao(), db.scanCheckpointDao()).scan(FileRef.Direct(root.absolutePath))
            val before = db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)
            val destination = File(root, "Target")
            Files.move(source.toPath(), destination.toPath())
            val unavailable = object : StorageGateway by gateway {
                override suspend fun listChildren(directory: FileRef): List<FileEntry> {
                    if (directory == FileRef.Direct(File(destination, "Notes").absolutePath)) error("Listing unavailable")
                    return gateway.listChildren(directory)
                }
            }
            var failed = false
            try { MovedFolderIndex(db.fileRecordDao(), unavailable).replace(FileRef.Direct(source.absolutePath), FileRef.Direct(destination.absolutePath), root.absolutePath) }
            catch (expected: IllegalStateException) { failed = true; assertThat(expected.message).contains("Listing unavailable") }
            assertThat(failed).isTrue()
            assertThat(db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)).containsExactlyElementsIn(before)
            assertThat(File(destination, "Notes/${text.name}").readText()).isEqualTo("Keep this scene")
        } finally { db.close() }
    }
}
