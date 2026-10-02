package com.pocketsteward.app.navigation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.db.TaskRun
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LargeTaskReadTest {
    @Test fun sixteenThousandOperationPlanSurvivesChunkedReadAndHasSmallOverview() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = database.taskRunDao()
            val operations = List(16_000) { n -> PlannedOperation.Move(
                FileRef.Direct("/storage/emulated/0/Download/Lilith 🌙 manuscript $n.txt"),
                FileRef.Direct("/storage/emulated/0/Documents/Lilith/Manuscript/Lilith 🌙 manuscript $n.txt"),
                "Reviewed project assignment",
            ) }
            val encoded = DurablePlanCodec.encode("Organize Downloads 🌙", operations)
            assertTrue(encoded.toByteArray().size > 2 * 1024 * 1024)
            val id = dao.insert(task(encoded, TaskRunStatus.CANCELLED))
            val overview = dao.observeOverviews().first().single()
            assertEquals(16_000, overview.operationCount)
            assertTrue(overview.hasDurablePlan)
            assertEquals("", dao.metadataById(id)!!.planJson)
            assertEquals(encoded, dao.getById(id)!!.planJson)
            assertEquals(encoded, dao.plansAfter(0).single().planJson)
            assertEquals(16_000, DurablePlanCodec.decodeOrNull(dao.getById(id)!!.planJson)!!.operations.size)
            dao.update(dao.getById(id)!!.copy(status = TaskRunStatus.RUNNING))
            assertEquals(encoded, dao.getRunning().single().planJson)
            assertEquals(encoded, dao.getRunsNeedingRecovery().single().planJson)
            assertNull(dao.getById(id + 1))
        } finally { database.close() }
    }

    @Test fun byteChunksPreserveUnicodeAtBoundaryAndExactMultiples() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val dao = database.taskRunDao()
            for (plan in listOf("", "a".repeat(65_536), "a".repeat(65_535) + "🌙" + "résumé".repeat(20000))) {
                val id = dao.insert(task(plan, TaskRunStatus.COMPLETED))
                assertEquals(plan, dao.getById(id)!!.planJson)
            }
        } finally { database.close() }
    }

    private fun task(plan: String, status: TaskRunStatus) = TaskRun(
        requestText = "Sort downloads", startedAt = 1, completedAt = null,
        status = status, scanSnapshotId = null, planJson = plan, summary = null,
        scopeRootRef = "/storage/emulated/0/Download", storageAccessMode = StorageAccessMode.DIRECT,
        undoCompletedAt = null,
    )
}
