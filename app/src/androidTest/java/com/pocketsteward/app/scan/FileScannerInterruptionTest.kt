package com.pocketsteward.app.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.ScanStatus
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.FileRef
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileScannerInterruptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "scanner-interruption").apply {
            deleteRecursively()
            check(mkdirs())
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        root.deleteRecursively()
    }

    @Test
    fun cancellationPersistsQueueAndResumeFinishesWithoutDuplicateRows() = runBlocking {
        repeat(5) { folderIndex ->
            val folder = File(root, "folder-$folderIndex").apply { mkdirs() }
            repeat(20) { fileIndex ->
                File(folder, "file-$fileIndex.txt").writeText("$folderIndex:$fileIndex")
            }
        }
        repeat(10) { index ->
            File(root, "root-$index.txt").writeText("root:$index")
        }

        val rootRef = FileRef.Direct(root.absolutePath)
        val scanner = FileScanner(
            gateway = DirectStorageGateway(context),
            fileRecordDao = db.fileRecordDao(),
            scanCheckpointDao = db.scanCheckpointDao(),
        )

        var progressCallbacks = 0
        val cancelled = runCatching {
            scanner.scan(rootRef) {
                progressCallbacks++
                if (progressCallbacks == 1) {
                    throw CancellationException("synthetic process/lifecycle interruption")
                }
            }
        }
        assertThat(cancelled.exceptionOrNull()).isInstanceOf(CancellationException::class.java)

        val paused = db.scanCheckpointDao().get(root.absolutePath)
        assertThat(paused).isNotNull()
        assertThat(paused!!.status).isEqualTo(ScanStatus.PAUSED)
        assertThat(paused.pendingDirectoriesJson).isNotEmpty()

        val durableBeforeResume = db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)
        assertThat(durableBeforeResume).isNotEmpty()
        assertThat(durableBeforeResume.size).isLessThan(116)

        // New scanner instance stands in for process recreation.
        FileScanner(
            gateway = DirectStorageGateway(context),
            fileRecordDao = db.fileRecordDao(),
            scanCheckpointDao = db.scanCheckpointDao(),
        ).scan(rootRef)

        val completed = db.scanCheckpointDao().get(root.absolutePath)
        assertThat(completed?.status).isEqualTo(ScanStatus.COMPLETED)

        val all = db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)
        // 1 root + 5 folders + 110 files.
        assertThat(all).hasSize(116)
        assertThat(all.map { it.stableRef }.distinct()).hasSize(116)
        assertThat(db.fileRecordDao().getFilesUnderScopeRoot(root.absolutePath)).hasSize(110)
    }

    @Test
    fun scannerHandlesLongUnicodeNamesAndHugeSparseFilesWithoutReadingPayload() = runBlocking {
        val longName = "é-🦇-" + "a".repeat(180) + ".txt"
        val longFile = File(root, longName).apply { writeText("tiny") }

        val sparse = File(root, "huge-sparse.bin")
        RandomAccessFile(sparse, "rw").use { it.setLength(2L * 1024L * 1024L * 1024L) }

        val empty = File(root, "empty").apply { mkdirs() }
        File(empty, "日本語-🙂.md").writeText("hello")

        val rootRef = FileRef.Direct(root.absolutePath)
        FileScanner(
            gateway = DirectStorageGateway(context),
            fileRecordDao = db.fileRecordDao(),
            scanCheckpointDao = db.scanCheckpointDao(),
        ).scan(rootRef)

        val files = db.fileRecordDao().getFilesUnderScopeRoot(root.absolutePath)
        assertThat(files.map { it.displayName }).contains(longFile.name)
        assertThat(files.map { it.displayName }).contains("日本語-🙂.md")
        assertThat(files.first { it.displayName == "huge-sparse.bin" }.sizeBytes)
            .isEqualTo(2L * 1024L * 1024L * 1024L)
    }
}
