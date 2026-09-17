package com.pocketsteward.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScanCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: ScanCheckpoint)

    @Query("SELECT * FROM scan_checkpoints WHERE scopeRootRef = :scopeRootRef")
    suspend fun get(scopeRootRef: String): ScanCheckpoint?

    @Query("DELETE FROM scan_checkpoints WHERE scopeRootRef = :scopeRootRef")
    suspend fun clear(scopeRootRef: String)
}
