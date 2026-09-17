package com.pocketsteward.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [FileRecord::class, TaskRun::class, MutationRecord::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(AppDatabase.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao
    abstract fun taskRunDao(): TaskRunDao
    abstract fun mutationRecordDao(): MutationRecordDao

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
    }

    companion object {
        private const val DATABASE_NAME = "pocket_steward.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }
    }
}
