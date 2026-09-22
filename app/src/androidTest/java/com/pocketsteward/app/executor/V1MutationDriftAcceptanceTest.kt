package com.pocketsteward.app.executor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.MutationStatus
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V1MutationDriftAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var destinationDir: File
    private lateinit var gateway: DirectStorageGateway

    @Before
    fun setUp() {
        root = File(context.cacheDir, "mutation-drift-source").apply {
            deleteRecursively()
            check(mkdirs())
        }
        destinationDir = File(context.cacheDir, "mutation-drift-destination").apply {
            deleteRecursively()
            check(mkdirs())
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = DirectStorageGateway(context)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        root.deleteRecursively()
        destinationDir.deleteRecursively()
    }

    @Test
    fun sourceModifiedAfterApprovalIsRefusedWithoutMovingEitherVersion() = runBlocking {
        val sourceFile = File(root, "report.pdf").apply {
            writeText("approved bytes")
            setLastModified(1_700_000_000_000L)
        }
        val destinationFile = File(destinationDir, sourceFile.name)

        val rootRef = FileRef.Direct(root.absolutePath)
        FileScanner(gateway, db.fileRecordDao(), db.scanCheckpointDao()).scan(rootRef)

        val operation = PlannedOperation.Move(
            source = FileRef.Direct(sourceFile.absolutePath),
            destination = FileRef.Direct(destinationFile.absolutePath),
            reason = "drift test",
        )
        val index = CompositeFileIndex(
            listOf(
                InMemoryFileIndex(db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)),
                SingleFolderIndex(
                    FileRef.Direct(destinationDir.absolutePath),
                    gateway.listChildren(FileRef.Direct(destinationDir.absolutePath)),
                ),
            ),
        )
        assertThat(PlanValidator.validate(listOf(operation), index).rejected).isEmpty()

        val executor = PlanExecutor(
            gateway,
            db.fileRecordDao(),
            db.taskRunDao(),
            db.mutationRecordDao(),
        )
        val taskId = executor.enqueueApproved(
            AgentPlan("source drift", listOf(operation)),
            root.absolutePath,
            StorageAccessMode.DIRECT,
            index,
        )

        // Same path, changed after the user approved the preview.
        sourceFile.writeText("changed after approval and now a different size")
        sourceFile.setLastModified(1_800_000_000_000L)

        val result = executor.resume(taskId)

        assertThat(result.filesMoved).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
        assertThat(sourceFile.readText()).contains("changed after approval")
        assertThat(destinationFile.exists()).isFalse()
        assertThat(db.taskRunDao().getById(taskId)?.status).isEqualTo(TaskRunStatus.FAILED)

        val journal = db.mutationRecordDao().getForTaskRun(taskId).single()
        assertThat(journal.status).isEqualTo(MutationStatus.FAILED)
        assertThat(journal.error).contains("changed after approval")
    }

    @Test
    fun destinationCreatedAfterApprovalStopsForReviewWithoutOverwrite() = runBlocking {
        val sourceFile = File(root, "report.pdf").apply {
            writeText("source")
            setLastModified(1_700_000_000_000L)
        }
        val destinationFile = File(destinationDir, sourceFile.name)

        val rootRef = FileRef.Direct(root.absolutePath)
        FileScanner(gateway, db.fileRecordDao(), db.scanCheckpointDao()).scan(rootRef)

        val operation = PlannedOperation.Move(
            FileRef.Direct(sourceFile.absolutePath),
            FileRef.Direct(destinationFile.absolutePath),
            "concurrent destination",
        )
        val index = CompositeFileIndex(
            listOf(
                InMemoryFileIndex(db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)),
                SingleFolderIndex(
                    FileRef.Direct(destinationDir.absolutePath),
                    gateway.listChildren(FileRef.Direct(destinationDir.absolutePath)),
                ),
            ),
        )

        val executor = PlanExecutor(
            gateway,
            db.fileRecordDao(),
            db.taskRunDao(),
            db.mutationRecordDao(),
        )
        val taskId = executor.enqueueApproved(
            AgentPlan("destination race", listOf(operation)),
            root.absolutePath,
            StorageAccessMode.DIRECT,
            index,
        )

        destinationFile.writeText("newer unrelated file")

        val result = executor.resume(taskId)

        assertThat(result.filesMoved).isEqualTo(0)
        assertThat(result.failed).isEqualTo(1)
        assertThat(sourceFile.readText()).isEqualTo("source")
        assertThat(destinationFile.readText()).isEqualTo("newer unrelated file")
        assertThat(db.taskRunDao().getById(taskId)?.status).isEqualTo(TaskRunStatus.NEEDS_REVIEW)

        val journal = db.mutationRecordDao().getForTaskRun(taskId).single()
        assertThat(journal.status).isEqualTo(MutationStatus.NEEDS_REVIEW)
        assertThat(journal.error).contains("destination exists")
    }
}
