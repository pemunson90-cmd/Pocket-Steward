package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_records")
data class FileRecord(
    @PrimaryKey val stableRef: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val hash: String? = null
)
