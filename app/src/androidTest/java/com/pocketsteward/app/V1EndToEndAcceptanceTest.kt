package com.pocketsteward.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.executor.InMemoryFileIndex
import com.pocketsteward.app.executor.PlanExecutor
import com.pocketsteward.app.executor.UndoExecutor
import com.pocketsteward.app.intent.DeterministicIntentParser
import com.pocketsteward.app.intent.IntentParseResult
import com.pocketsteward.app.intent.IntentPlanGenerator
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanSelection
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.scan.FileScanner
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V1EndToEndAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var gateway: DirectStorageGateway

    @Before
    fun setUp() {
        root = File(context.cacheDir, "v1-e2e-acceptance").apply {
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
    }

    @Test
    fun canonicalV1Flow_scansPlansDeselectsExecutesAndUndoesAThousandFiles() = runBlocking {
        seedCanonicalCorpus()

        val rootRef = FileRef.Direct(root.absolutePath)
        val scanner = FileScanner(
            gateway = gateway,
            fileRecordDao = db.fileRecordDao(),
            scanCheckpointDao = db.scanCheckpointDao(),
        )
        scanner.scan(rootRef)

        val scannedFiles = db.fileRecordDao().getFilesUnderScopeRoot(root.absolutePath)
        assertThat(scannedFiles).hasSize(1_000)

        val request =
            "Organize the obvious files by type. Put APKs together, put PDFs and documents together, " +
                "keep images separate, and leave anything uncertain alone."
        val parsed = DeterministicIntentParser.parse(request) as IntentParseResult.Parsed
        val generated = IntentPlanGenerator.generate(
            scopeRoot = rootRef,
            records = scannedFiles,
            projectKeywords = emptyList(),
            intent = parsed.intent,
        )

        val proposedMoves = generated.plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(proposedMoves).hasSize(750)
        assertThat(proposedMoves.none { it.source.toString().contains("mystery-") }).isTrue()

        val indexed = db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)
        val validated = PlanValidator.validate(
            generated.plan.operations,
            InMemoryFileIndex(indexed),
        )
        assertThat(validated.rejected).isEmpty()

        // Acceptance item 6: arbitrary deselection must survive dependency pruning.
        val firstMoveIndex = validated.accepted.indexOfFirst { it is PlannedOperation.Move }
        val selected = PlanSelection.setSelected(
            operations = validated.accepted,
            current = PlanSelection.safeSelected(validated.accepted),
            index = firstMoveIndex,
            selected = false,
        )
        val approved = PlanSelection.selectedOperations(validated.accepted, selected)
        assertThat(approved).hasSize(validated.accepted.size - 1)

        val executor = PlanExecutor(
            gateway = gateway,
            fileRecordDao = db.fileRecordDao(),
            taskRunDao = db.taskRunDao(),
            mutationRecordDao = db.mutationRecordDao(),
        )
        val summary = executor.execute(
            plan = AgentPlan(request, approved),
            scopeRootRef = root.absolutePath,
            storageAccessMode = StorageAccessMode.DIRECT,
            index = InMemoryFileIndex(indexed),
        )

        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.filesMoved).isEqualTo(749)
        assertThat(summary.foldersCreated).isEqualTo(3)
        assertThat(summary.succeededTotal).isEqualTo(752)

        // 250 unknown files plus the one explicitly deselected known file remain at root.
        val filesStillAtRoot = root.listFiles().orEmpty().count { it.isFile }
        assertThat(filesStillAtRoot).isEqualTo(251)
        assertThat(File(root, "APKs").isDirectory).isTrue()
        assertThat(File(root, "Documents").isDirectory).isTrue()
        assertThat(File(root, "Images").isDirectory).isTrue()

        val task = db.taskRunDao().getById(summary.taskRunId)
        assertThat(task?.status).isEqualTo(TaskRunStatus.COMPLETED)
        assertThat(db.mutationRecordDao().getForTaskRun(summary.taskRunId))
            .hasSize(approved.size)

        val undo = UndoExecutor(
            fileRecordDao = db.fileRecordDao(),
            taskRunDao = db.taskRunDao(),
            mutationRecordDao = db.mutationRecordDao(),
            gatewayFor = { gateway },
        ).undo(summary.taskRunId)

        assertThat(undo.complete).isTrue()
        assertThat(root.listFiles().orEmpty().count { it.isFile }).isEqualTo(1_000)
        assertThat(File(root, "APKs").exists()).isFalse()
        assertThat(File(root, "Documents").exists()).isFalse()
        assertThat(File(root, "Images").exists()).isFalse()
        assertThat(db.taskRunDao().getById(summary.taskRunId)?.status)
            .isEqualTo(TaskRunStatus.UNDONE)
    }

    @Test
    fun durableTask_canPauseThenResumeWithFreshExecutorWithoutReplayingJournaledWork() = runBlocking {
        repeat(60) { index ->
            File(root, "document-$index.pdf").writeText("document $index")
        }

        val rootRef = FileRef.Direct(root.absolutePath)
        FileScanner(gateway, db.fileRecordDao(), db.scanCheckpointDao()).scan(rootRef)
        val records = db.fileRecordDao().getFilesUnderScopeRoot(root.absolutePath)
        val intent = (DeterministicIntentParser.parse("organize documents by type") as IntentParseResult.Parsed).intent
        val generated = IntentPlanGenerator.generate(
            scopeRoot = rootRef,
            records = records,
            projectKeywords = emptyList(),
            intent = intent,
        )
        val indexed = db.fileRecordDao().getAllUnderScopeRoot(root.absolutePath)
        val plan = AgentPlan("resume acceptance", generated.plan.operations)

        val executorBeforeRestart = PlanExecutor(
            gateway,
            db.fileRecordDao(),
            db.taskRunDao(),
            db.mutationRecordDao(),
        )
        val taskId = executorBeforeRestart.enqueueApproved(
            plan = plan,
            scopeRootRef = root.absolutePath,
            storageAccessMode = StorageAccessMode.DIRECT,
            index = InMemoryFileIndex(indexed),
        )

        var completed = 0
        val paused = executorBeforeRestart.resume(
            taskRunId = taskId,
            shouldPause = { completed >= 20 },
            onProgress = { done, _ -> completed = done },
        )
        assertThat(paused.cancelled).isTrue()
        assertThat(db.taskRunDao().getById(taskId)?.status).isEqualTo(TaskRunStatus.CANCELLED)

        val journalBeforeResume = db.mutationRecordDao().getForTaskRun(taskId)
        assertThat(journalBeforeResume.size).isAtLeast(20)
        assertThat(journalBeforeResume.map { it.sequence }.distinct())
            .hasSize(journalBeforeResume.size)

        // New executor instance stands in for a process/app restart.
        val executorAfterRestart = PlanExecutor(
            gateway,
            db.fileRecordDao(),
            db.taskRunDao(),
            db.mutationRecordDao(),
        )
        val finished = executorAfterRestart.resume(taskId)
        assertThat(finished.cancelled).isFalse()
        assertThat(finished.failed).isEqualTo(0)
        assertThat(db.taskRunDao().getById(taskId)?.status).isEqualTo(TaskRunStatus.COMPLETED)

        val finalJournal = db.mutationRecordDao().getForTaskRun(taskId)
        assertThat(finalJournal).hasSize(plan.operations.size)
        assertThat(finalJournal.map { it.sequence }.distinct()).hasSize(plan.operations.size)
        assertThat(File(root, "Documents").listFiles().orEmpty()).hasLength(60)
    }

    @Test
    fun coreOrganizerHasNoDirectInternetPermission() {
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(
                Manifest.permission.INTERNET,
                context.packageName,
            ),
        )
    }

    private fun seedCanonicalCorpus() {
        repeat(250) { index ->
            File(root, "installer-$index.apk").writeBytes(byteArrayOf(1, 2, 3, (index % 251).toByte()))
            File(root, "document-$index.pdf").writeBytes(byteArrayOf(4, 5, 6, (index % 251).toByte()))
            File(root, "photo-$index.jpg").writeBytes(byteArrayOf(7, 8, 9, (index % 251).toByte()))
            File(root, "mystery-$index.unknownblob").writeBytes(byteArrayOf(10, 11, 12, (index % 251).toByte()))
        }
    }
}
