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
                // Preserve the old one-scope-per-file mapping while file_records
                // is rebuilt without scopeRootRef.
                db.execSQL("""
                    CREATE TABLE `file_scopes_legacy` (
                        `fileRef` TEXT NOT NULL,
                        `scopeRoot` TEXT NOT NULL,
                        PRIMARY KEY(`fileRef`, `scopeRoot`)
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT OR IGNORE INTO `file_scopes_legacy` (`fileRef`, `scopeRoot`)
                    SELECT `stableRef`, `scopeRootRef` FROM `file_records`
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE `file_records_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `stableRef` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `extension` TEXT NOT NULL,
                        `mimeType` TEXT,
                        `absolutePathOrUri` TEXT NOT NULL,
                        `parentRef` TEXT,
                        `sizeBytes` INTEGER NOT NULL,
                        `createdAt` INTEGER,
                        `modifiedAt` INTEGER,
                        `lastScannedAt` INTEGER NOT NULL,
                        `isDirectory` INTEGER NOT NULL,
                        `isHidden` INTEGER NOT NULL,
                        `mediaType` TEXT,
                        `width` INTEGER,
                        `height` INTEGER,
                        `durationMs` INTEGER,
                        `apkPackageName` TEXT,
                        `apkVersionName` TEXT,
                        `sha256` TEXT,
                        `quickFingerprint` TEXT,
                        `textPreview` TEXT,
                        `classification` TEXT,
                        `classificationConfidence` REAL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO `file_records_new` (
                        `id`, `stableRef`, `displayName`, `extension`, `mimeType`,
                        `absolutePathOrUri`, `parentRef`, `sizeBytes`, `createdAt`,
                        `modifiedAt`, `lastScannedAt`, `isDirectory`, `isHidden`,
                        `mediaType`, `width`, `height`, `durationMs`, `apkPackageName`,
                        `apkVersionName`, `sha256`, `quickFingerprint`, `textPreview`,
                        `classification`, `classificationConfidence`
                    )
                    SELECT
                        `id`, `stableRef`, `displayName`, `extension`, `mimeType`,
                        `absolutePathOrUri`, `parentRef`, `sizeBytes`, `createdAt`,
                        `modifiedAt`, `lastScannedAt`, `isDirectory`, `isHidden`,
                        `mediaType`, `width`, `height`, `durationMs`, `apkPackageName`,
                        `apkVersionName`, `sha256`, `quickFingerprint`, `textPreview`,
                        `classification`, `classificationConfidence`
                    FROM `file_records`
                """.trimIndent())

                db.execSQL("DROP TABLE `file_records`")
                db.execSQL("ALTER TABLE `file_records_new` RENAME TO `file_records`")
                db.execSQL("CREATE UNIQUE INDEX `index_file_records_stableRef` ON `file_records` (`stableRef`)")

                db.execSQL("""
                    CREATE TABLE `file_scopes` (
                        `fileRef` TEXT NOT NULL,
                        `scopeRoot` TEXT NOT NULL,
                        PRIMARY KEY(`fileRef`, `scopeRoot`),
                        FOREIGN KEY(`fileRef`) REFERENCES `file_records`(`stableRef`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX `index_file_scopes_fileRef` ON `file_scopes` (`fileRef`)")
                db.execSQL("""
                    INSERT INTO `file_scopes` (`fileRef`, `scopeRoot`)
                    SELECT `fileRef`, `scopeRoot` FROM `file_scopes_legacy`
                """.trimIndent())
                db.execSQL("DROP TABLE `file_scopes_legacy`")
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
