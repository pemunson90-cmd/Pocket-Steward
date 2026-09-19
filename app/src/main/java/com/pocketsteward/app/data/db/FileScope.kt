package com.pocketsteward.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "file_scopes",
    primaryKeys = ["fileRef", "scopeRoot"],
    foreignKeys = [
        ForeignKey(
            entity = FileRecord::class,
            parentColumns = ["stableRef"],
            childColumns = ["fileRef"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class FileScope(
    val fileRef: String,
    val scopeRoot: String
)
