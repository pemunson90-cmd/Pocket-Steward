package com.pocketsteward.app.executor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.FileScope
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.DirectStorageTestFixture
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-runtime acceptance slice for the core V1 safety loop:
 *
 * real file -> validated typed plan -> executor -> write-ahead journal ->
 * actual filesystem mutation -> reverse-order undo -> original structure.
 *
 * Uses a dedicated shared-storage instrumentation sandbox so the real Direct
 * mode protection boundary is exercised without touching user documents.
 */
@RunWith(AndroidJUnit4::class)
class V1FilesystemRoundTripTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var gateway: DirectStorageGateway
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = DirectStorageGateway(context)
        root = DirectStorageTestFixture.freshRoot(context, "v1-roundtrip")
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        DirectStorageTestFixture.clean(root)
    }

    @Test
    fun approvedMoveTaskExecutesAndUndoRestoresOriginalTree() = runBlocking {
        val source = File(root, "report.txt").apply {
            writeText("Pocket Steward round-trip acceptance payload")
        }
        val destinationDirectory = File(root, "Documents")
        val destination = File(destinationDirectory, source.name)

        val rootRef = FileRef.Direct(root.absolutePath)
        val sourceRef = FileRef.Direct(source.absolutePath)
        val destinationRef = FileRef.Direct(destination.absolutePath)

        val records = listOf(
            record(root, parent = root.parentFile),
            record(source, parent = root),
        )
        val fileDao = database.fileRecordDao()
        records.forEach { record ->
            fileDao.upsert(record)
            fileDao.insertScopeTag(FileScope(record.stableRef, root.absolutePath))
        }

        val operations = listOf(
            PlannedOperation.CreateDirectory(
                parent = rootRef,
                name = destinationDirectory.name,
                reason = "V1 acceptance destination",
            ),
            PlannedOperation.Move(
                source = sourceRef,
                destination = destinationRef,
                reason = "V1 acceptance move",
            ),
        )
        val executor = PlanExecutor(
            gateway = gateway,
            fileRecordDao = fileDao,
            taskRunDao = database.taskRunDao(),
            mutationRecordDao = database.mutationRecordDao(),
        )

        val summary = executor.execute(
            plan = AgentPlan("V1 Android round trip", operations),
            scopeRootRef = root.absolutePath,
            storageAccessMode = StorageAccessMode.DIRECT,
            index = InMemoryFileIndex(records),
        )

        assertEquals(1, summary.foldersCreated)
        assertEquals(1, summary.filesMoved)
        assertEquals(0, summary.failed)
        assertFalse(source.exists())
        assertTrue(destination.isFile)
        assertEquals(
            "Pocket Steward round-trip acceptance payload",
            destination.readText(),
        )

        val undo = UndoExecutor(
            fileRecordDao = fileDao,
            taskRunDao = database.taskRunDao(),
            mutationRecordDao = database.mutationRecordDao(),
            gatewayFor = { gateway },
        ).undo(summary.taskRunId)

        assertTrue(undo.complete)
        assertEquals(2, undo.undone)
        assertTrue(source.isFile)
        assertEquals(
            "Pocket Steward round-trip acceptance payload",
            source.readText(),
        )
        assertFalse(destination.exists())
        assertFalse(destinationDirectory.exists())
        assertEquals(
            TaskRunStatus.UNDONE,
            database.taskRunDao().getById(summary.taskRunId)?.status,
        )
    }

    @Test
    fun sourceChangedAfterApprovalFailsClosedBeforeFilesystemMutation() = runBlocking {
        val source = File(root, "mutable.txt").apply { writeText("approved") }
        val destinationDirectory = File(root, "Documents").apply { mkdirs() }
        val destination = File(destinationDirectory, source.name)

        val sourceRef = FileRef.Direct(source.absolutePath)
        val destinationRef = FileRef.Direct(destination.absolutePath)
        val records = listOf(
            record(root, parent = root.parentFile),
            record(source, parent = root),
            record(destinationDirectory, parent = root),
        )
        val fileDao = database.fileRecordDao()
        records.forEach { record ->
            fileDao.upsert(record)
            fileDao.insertScopeTag(FileScope(record.stableRef, root.absolutePath))
        }

        val operation = PlannedOperation.Move(
            source = sourceRef,
            destination = destinationRef,
            reason = "approval precondition acceptance test",
        )
        val executor = PlanExecutor(
            gateway = gateway,
            fileRecordDao = fileDao,
            taskRunDao = database.taskRunDao(),
            mutationRecordDao = database.mutationRecordDao(),
        )

        val taskRunId = executor.enqueueApproved(
            plan = AgentPlan("Move only if unchanged", listOf(operation)),
            scopeRootRef = root.absolutePath,
            storageAccessMode = StorageAccessMode.DIRECT,
            index = InMemoryFileIndex(records),
        )

        // Simulate another app/user editing the source after approval but
        // before the durable runner gets CPU time.
        source.writeText("changed after approval and intentionally longer")

        val summary = executor.resume(taskRunId)

        assertEquals(0, summary.filesMoved)
        assertEquals(1, summary.failed)
        assertTrue(source.isFile)
        assertFalse(destination.exists())
        assertTrue(summary.failures.single().reason.contains("changed after approval"))
        assertEquals(
            TaskRunStatus.FAILED,
            database.taskRunDao().getById(taskRunId)?.status,
        )
    }

    private fun record(file: File, parent: File?): FileRecord = FileRecord(
        stableRef = file.absolutePath,
        displayName = file.name,
        extension = file.extension.lowercase(),
        mimeType = null,
        absolutePathOrUri = file.absolutePath,
        parentRef = parent?.absolutePath,
        sizeBytes = if (file.isDirectory) 0L else file.length(),
        createdAt = null,
        modifiedAt = file.lastModified().takeIf { it > 0L },
        lastScannedAt = System.currentTimeMillis(),
        isDirectory = file.isDirectory,
        isHidden = file.isHidden || file.name.startsWith("."),
    )
}
