package com.pocketsteward.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileRecordDaoScanBatchTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: FileRecordDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fileRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun scanBatchOverSqliteBindLimitInsertsAndReusesIds() = runBlocking {
        val first = (0 until 1_250).map { index ->
            record(
                ref = "/Download/file-$index.bin",
                size = index.toLong(),
                modified = 100L,
                generation = 1_000L,
            )
        }

        val ids = dao.upsertAllFromScan(first)
        assertThat(ids).hasSize(1_250)
        assertThat(ids.distinct()).hasSize(1_250)

        val enriched = dao.getByStableRef("/Download/file-800.bin")!!.copy(
            sha256 = "kept-hash",
            textPreview = "keep me",
        )
        dao.update(enriched)

        val second = first.map {
            it.copy(lastScannedAt = 2_000L)
        }
        val secondIds = dao.upsertAllFromScan(second)

        assertThat(secondIds).containsExactlyElementsIn(ids).inOrder()
        val preserved = dao.getByStableRef("/Download/file-800.bin")!!
        assertThat(preserved.sha256).isEqualTo("kept-hash")
        assertThat(preserved.textPreview).isEqualTo("keep me")
        assertThat(preserved.lastScannedAt).isEqualTo(2_000L)
    }

    @Test
    fun changedCheapIdentityDropsStaleDerivedMetadata() = runBlocking {
        val original = record(
            ref = "/Download/report.pdf",
            size = 100L,
            modified = 100L,
            generation = 1_000L,
        ).copy(
            sha256 = "old-hash",
            textPreview = "old text",
            width = 123,
        )
        dao.upsertAllFromScan(listOf(original))

        dao.upsertAllFromScan(
            listOf(
                record(
                    ref = "/Download/report.pdf",
                    size = 101L,
                    modified = 200L,
                    generation = 2_000L,
                ),
            ),
        )

        val updated = dao.getByStableRef("/Download/report.pdf")!!
        assertThat(updated.sha256).isNull()
        assertThat(updated.textPreview).isNull()
        assertThat(updated.width).isNull()
        assertThat(updated.sizeBytes).isEqualTo(101L)
    }

    private fun record(
        ref: String,
        size: Long,
        modified: Long,
        generation: Long,
    ) = FileRecord(
        stableRef = ref,
        displayName = ref.substringAfterLast('/'),
        extension = ref.substringAfterLast('.', ""),
        mimeType = "application/octet-stream",
        absolutePathOrUri = ref,
        parentRef = "/Download",
        sizeBytes = size,
        createdAt = null,
        modifiedAt = modified,
        lastScannedAt = generation,
        isDirectory = false,
        isHidden = false,
    )
}
