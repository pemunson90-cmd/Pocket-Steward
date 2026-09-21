package com.pocketsteward.app.content.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Rebuildable derived cache for local content search.
 *
 * This database is intentionally separate from pocket_steward.db. A schema
 * reset here may cost re-indexing time, but can never erase mutation journals,
 * task history, Undo state, or scan checkpoints.
 */
@Database(
    entities = [
        IndexedDocument::class,
        IndexedSegment::class,
        IndexedSegmentFts::class,
        ContentIndexState::class,
        ContentIndexJob::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ContentSearchDatabase : RoomDatabase() {
    abstract fun contentIndexDao(): ContentIndexDao

    companion object {
        private const val DATABASE_NAME = "content_search.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS content_index_jobs (
                        sourceRoot TEXT NOT NULL PRIMARY KEY,
                        status TEXT NOT NULL,
                        cursorRef TEXT,
                        eligibleCount INTEGER NOT NULL,
                        processedCount INTEGER NOT NULL,
                        reused INTEGER NOT NULL,
                        extracted INTEGER NOT NULL,
                        unsupported INTEGER NOT NULL,
                        failed INTEGER NOT NULL,
                        removedStale INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        extractorVersion INTEGER NOT NULL,
                        error TEXT
                    )
                    """.trimIndent(),
                )
            }
        }


        @Volatile
        private var instance: ContentSearchDatabase? = null

        fun getInstance(context: Context): ContentSearchDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContentSearchDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }

        fun delete(context: Context): Boolean {
            instance?.close()
            instance = null
            return context.applicationContext.deleteDatabase(DATABASE_NAME)
        }
    }
}
