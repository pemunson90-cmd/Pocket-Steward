package com.pocketsteward.app.content.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
    exportSchema = false,
)
abstract class ContentSearchDatabase : RoomDatabase() {
    abstract fun contentIndexDao(): ContentIndexDao

    companion object {
        private const val DATABASE_NAME = "content_search.db"

        @Volatile
        private var instance: ContentSearchDatabase? = null

        fun getInstance(context: Context): ContentSearchDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContentSearchDatabase::class.java,
                    DATABASE_NAME,
                )
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
