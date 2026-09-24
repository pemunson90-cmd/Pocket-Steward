package com.pocketsteward.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FileRecord::class, FileScope::class, TaskRun::class, MutationRecord::class, ScanCheckpoint::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun mutationRecordDao(): MutationRecordDao
    abstract fun scanCheckpointDao(): ScanCheckpointDao

    class Converters {
        @TypeConverter
        fun fromTaskRunStatus(value: TaskRunStatus): String = value.name

        @TypeConverter
        fun toTaskRunStatus(value: String): TaskRunStatus = TaskRunStatus.valueOf(value)

        @TypeConverter
        fun fromMutationOperationType(value: MutationOperationType): String = value.name

        @TypeConverter
        fun toMutationOperationType(value: String): MutationOperationType =
            MutationOperationType.valueOf(value)

        @TypeConverter
        fun fromMutationStatus(value: MutationStatus): String = value.name

        @TypeConverter
        fun toMutationStatus(value: String): MutationStatus = MutationStatus.valueOf(value)

        @TypeConverter
        fun fromUndoState(value: UndoState): String = value.name

        @TypeConverter
        fun toUndoState(value: String): UndoState = UndoState.valueOf(value)

        @TypeConverter
        fun fromStorageAccessMode(value: com.pocketsteward.app.storage.StorageAccessMode): String = value.name

        @TypeConverter
        fun toStorageAccessMode(value: String): com.pocketsteward.app.storage.StorageAccessMode =
            com.pocketsteward.app.storage.StorageAccessMode.valueOf(value)

        @TypeConverter
        fun fromScanStatus(value: ScanStatus): String = value.name

        @TypeConverter
        fun toScanStatus(value: String): ScanStatus = ScanStatus.valueOf(value)
    }

    companion object {
        private const val DATABASE_NAME = "pocket_steward.db"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Statements live in Migrations so a JVM test can execute
                // them against real SQLite. Order and text are unchanged.
                Migrations.V3_TO_V4.forEach(db::execSQL)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
