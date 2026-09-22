package com.pocketsteward.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real 3 -> 4 migration against a v3-shaped SQLite database.
 *
 * This is deliberately data-preservation focused: v4 replaced the
 * one-scope-per-file column with FileScope membership. Task history, mutation
 * journal and scan checkpoints are safety data and must survive untouched.
 * Opening the migrated file through Room also performs Room's own v4 schema
 * validation, so a structurally wrong migration fails this test before any
 * assertion runs.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigration3To4Test {
    private lateinit var context: Context
    private val dbName = "migration-3-4-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrationPreservesJournalHistoryCheckpointsAndBackfillsScopeMembership() = runBlocking {
        createVersion3Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            // Trigger open + migration + Room's schema validation.
            db.openHelper.writableDatabase

            val file = db.fileRecordDao().getByStableRef("/storage/emulated/0/Download/report.pdf")
            assertThat(file).isNotNull()
            assertThat(file!!.displayName).isEqualTo("report.pdf")
            assertThat(file.sha256).isEqualTo("sha-preserved")
            assertThat(file.textPreview).isEqualTo("preview-preserved")

            val scoped = db.fileRecordDao().getFilesUnderScopeRoot("/storage/emulated/0/Download")
            assertThat(scoped.map { it.stableRef })
                .containsExactly("/storage/emulated/0/Download/report.pdf")

            val task = db.taskRunDao().getById(7L)
            assertThat(task).isNotNull()
            assertThat(task!!.status).isEqualTo(TaskRunStatus.RUNNING)
            assertThat(task.planJson).isEqualTo("durable-plan-preserved")

            val journal = db.mutationRecordDao().getForTaskRun(7L)
            assertThat(journal).hasSize(1)
            assertThat(journal.single().sourceBefore).isEqualTo("D:/storage/emulated/0/Download/report.pdf")
            assertThat(journal.single().status).isEqualTo(MutationStatus.PENDING)
            assertThat(journal.single().sourceFingerprint).isEqualTo("fingerprint-preserved")

            val checkpoint = db.scanCheckpointDao()
                .get("/storage/emulated/0/Download")
            assertThat(checkpoint).isNotNull()
            assertThat(checkpoint!!.processedCount).isEqualTo(321)
            assertThat(checkpoint.status).isEqualTo(ScanStatus.PAUSED)

            // The old scopeRootRef column must actually be gone, not merely
            // ignored by the current entity.
            val columns = mutableListOf<String>()
            db.openHelper.readableDatabase
                .query("PRAGMA table_info(file_records)")
                .use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
                }
            assertThat(columns).doesNotContain("scopeRootRef")
            assertThat(columns).contains("stableRef")
        } finally {
            db.close()
        }
    }

    private fun createVersion3Database() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS file_records (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                stableRef TEXT NOT NULL,
                                scopeRootRef TEXT NOT NULL,
                                displayName TEXT NOT NULL,
                                extension TEXT NOT NULL,
                                mimeType TEXT,
                                absolutePathOrUri TEXT NOT NULL,
                                parentRef TEXT,
                                sizeBytes INTEGER NOT NULL,
                                createdAt INTEGER,
                                modifiedAt INTEGER,
                                lastScannedAt INTEGER NOT NULL,
                                isDirectory INTEGER NOT NULL,
                                isHidden INTEGER NOT NULL,
                                mediaType TEXT,
                                width INTEGER,
                                height INTEGER,
                                durationMs INTEGER,
                                apkPackageName TEXT,
                                apkVersionName TEXT,
                                sha256 TEXT,
                                quickFingerprint TEXT,
                                textPreview TEXT,
                                classification TEXT,
                                classificationConfidence REAL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_file_records_stableRef " +
                                "ON file_records(stableRef)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_file_records_scopeRootRef " +
                                "ON file_records(scopeRootRef)",
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS task_runs (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                requestText TEXT NOT NULL,
                                startedAt INTEGER NOT NULL,
                                completedAt INTEGER,
                                status TEXT NOT NULL,
                                scanSnapshotId INTEGER,
                                planJson TEXT NOT NULL,
                                summary TEXT,
                                scopeRootRef TEXT NOT NULL,
                                storageAccessMode TEXT NOT NULL,
                                undoCompletedAt INTEGER
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS mutation_records (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                taskRunId INTEGER NOT NULL,
                                sequence INTEGER NOT NULL,
                                operationType TEXT NOT NULL,
                                sourceBefore TEXT NOT NULL,
                                destinationAfter TEXT,
                                sourceFingerprint TEXT,
                                status TEXT NOT NULL,
                                executedAt INTEGER,
                                undoState TEXT NOT NULL,
                                undoAttemptedAt INTEGER,
                                error TEXT,
                                undoError TEXT,
                                FOREIGN KEY(taskRunId) REFERENCES task_runs(id)
                                    ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_mutation_records_taskRunId " +
                                "ON mutation_records(taskRunId)",
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS scan_checkpoints (
                                scopeRootRef TEXT NOT NULL,
                                pendingDirectoriesJson TEXT NOT NULL,
                                processedCount INTEGER NOT NULL,
                                status TEXT NOT NULL,
                                startedAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL,
                                PRIMARY KEY(scopeRootRef)
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO file_records (
                    id, stableRef, scopeRootRef, displayName, extension, mimeType,
                    absolutePathOrUri, parentRef, sizeBytes, createdAt, modifiedAt,
                    lastScannedAt, isDirectory, isHidden, mediaType, width, height,
                    durationMs, apkPackageName, apkVersionName, sha256,
                    quickFingerprint, textPreview, classification,
                    classificationConfidence
                ) VALUES (
                    11,
                    '/storage/emulated/0/Download/report.pdf',
                    '/storage/emulated/0/Download',
                    'report.pdf',
                    'pdf',
                    'application/pdf',
                    '/storage/emulated/0/Download/report.pdf',
                    '/storage/emulated/0/Download',
                    9876,
                    NULL,
                    1000,
                    2000,
                    0,
                    0,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    'sha-preserved',
                    'quick-preserved',
                    'preview-preserved',
                    'DOCUMENT',
                    1.0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO task_runs (
                    id, requestText, startedAt, completedAt, status,
                    scanSnapshotId, planJson, summary, scopeRootRef,
                    storageAccessMode, undoCompletedAt
                ) VALUES (
                    7, 'Organize test', 100, NULL, 'RUNNING',
                    NULL, 'durable-plan-preserved', 'still running',
                    '/storage/emulated/0/Download', 'DIRECT', NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO mutation_records (
                    id, taskRunId, sequence, operationType, sourceBefore,
                    destinationAfter, sourceFingerprint, status, executedAt,
                    undoState, undoAttemptedAt, error, undoError
                ) VALUES (
                    9, 7, 0, 'MOVE',
                    'D:/storage/emulated/0/Download/report.pdf',
                    'D:/storage/emulated/0/Documents/report.pdf',
                    'fingerprint-preserved',
                    'PENDING', NULL, 'NOT_AVAILABLE', NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO scan_checkpoints (
                    scopeRootRef, pendingDirectoriesJson, processedCount,
                    status, startedAt, updatedAt
                ) VALUES (
                    '/storage/emulated/0/Download',
                    '[]',
                    321,
                    'PAUSED',
                    10,
                    20
                )
                """.trimIndent(),
            )
        }
        helper.close()
    }
}
