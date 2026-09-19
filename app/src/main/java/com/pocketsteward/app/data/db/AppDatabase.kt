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
                // 1. Create the join table for file and scope references
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `file_scopes` (
                        `fileRef` TEXT NOT NULL, 
                        `scopeRoot` TEXT NOT NULL, 
                        PRIMARY KEY(`fileRef`, `scopeRoot`),
                        FOREIGN KEY(`fileRef`) REFERENCES `file_records`(`stableRef`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())

                // 2. Backfill the join table from the existing single-scope column
                db.execSQL("""
                    INSERT INTO `file_scopes` (`fileRef`, `scopeRoot`)
                    SELECT `stableRef`, `scopeRootRef` FROM `file_records`
                """.trimIndent())

                // 3. Recreate file_records without scopeRootRef to drop the column
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `file_records_new` (
                        `stableRef` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `modifiedMs` INTEGER NOT NULL,
                        `hash` TEXT,
                        PRIMARY KEY(`stableRef`)
                    )
                """.trimIndent())

                // 4. Migrate data to the new schema
                db.execSQL("""
                    INSERT INTO `file_records_new` (`stableRef`, `name`, `mimeType`, `sizeBytes`, `modifiedMs`, `hash`)
                    SELECT `stableRef`, `name`, `mimeType`, `sizeBytes`, `modifiedMs`, `hash` FROM `file_records`
                """.trimIndent())

                // 5. Swap tables
                db.execSQL("DROP TABLE `file_records`")
                db.execSQL("ALTER TABLE `file_records_new` RENAME TO `file_records`")
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
