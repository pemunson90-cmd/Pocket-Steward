package com.pocketsteward.app.executor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.SafStorageGateway
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.TestSafDocumentsProvider
import com.pocketsteward.app.storage.rawValue
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafPlanExecutorRoundTripTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var gateway: SafStorageGateway
    private lateinit var root: FileRef

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.contentResolver.call(
            Uri.parse("content://\${TestSafDocumentsProvider.AUTHORITY}"),
            TestSafDocumentsProvider.METHOD_RESET,
            null,
            null,
        )
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = SafStorageGateway(context)

        val tree = DocumentsContract.buildTreeDocumentUri(
            TestSafDocumentsProvider.AUTHORITY,
            TestSafDocumentsProvider.ROOT_ID,
        )
        root = gateway.rootOf(
            StorageScope.Tree(
                rootRef = FileRef.Saf(tree.toString()),
                displayName = "Test SAF",
            ),
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    @Test
    fun selectedTree_moveTask_executesJournalsAndUndoesBackToOriginalPath() = runBlocking {
        val written = gateway.writeTextFile(root, "source.txt", "saf-round-trip")
        assertThat(written).isInstanceOf(MutationResult.Success::class.java)
        val concreteSource = (written as MutationResult.Success).resultRef

        FileScanner(
            gateway = gateway,
            fileRecordDao = db.fileRecordDao(),
            scanCheckpointDao = db.scanCheckpointDao(),
        ).scan(root)

        val docs = FileRef.Child(root, "Docs")
        val destination = FileRef.Child(docs, "source.txt")
        val plan = AgentPlan(
            goal = "Move the selected-tree source into Docs",
            operations = listOf(
                PlannedOperation.CreateDirectory(
                    parent = root,
                    name = "Docs",
                    reason = "test destination",
                ),
                PlannedOperation.Move(
                    source = concreteSource,
                    destination = destination,
                    reason = "selected-tree move",
                ),
            ),
        )

        val executor = PlanExecutor(
            gateway = gateway,
            fileRecordDao = db.fileRecordDao(),
            taskRunDao = db.taskRunDao(),
            mutationRecordDao = db.mutationRecordDao(),
        )
        val summary = executor.execute(
            plan = plan,
            scopeRootRef = root.rawValue(),
            storageAccessMode = StorageAccessMode.SAF,
        )

        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.filesMoved).isEqualTo(1)
        assertThat(gateway.exists(concreteSource)).isFalse()
        assertThat(gateway.exists(destination)).isTrue()
        assertThat(readText(destination)).isEqualTo("saf-round-trip")
        assertThat(db.taskRunDao().getById(summary.taskRunId)?.status)
            .isEqualTo(TaskRunStatus.COMPLETED)

        val journal = db.mutationRecordDao().getForTaskRun(summary.taskRunId)
        assertThat(journal).hasSize(2)
        assertThat(journal.map { it.sequence }).containsExactly(0, 1).inOrder()

        val undo = UndoExecutor(
            fileRecordDao = db.fileRecordDao(),
            taskRunDao = db.taskRunDao(),
            mutationRecordDao = db.mutationRecordDao(),
            gatewayFor = { gateway },
        ).undo(summary.taskRunId)

        assertThat(undo.complete).isTrue()
        assertThat(undo.blocked).isEqualTo(0)

        val restored = FileRef.Child(root, "source.txt")
        assertThat(gateway.exists(restored)).isTrue()
        assertThat(readText(restored)).isEqualTo("saf-round-trip")
        assertThat(gateway.exists(docs)).isFalse()
        assertThat(db.taskRunDao().getById(summary.taskRunId)?.status)
            .isEqualTo(TaskRunStatus.UNDONE)
    }

    private suspend fun readText(ref: FileRef): String =
        gateway.openRead(ref).use { input ->
            String(input.readBytes(), StandardCharsets.UTF_8)
        }
}
