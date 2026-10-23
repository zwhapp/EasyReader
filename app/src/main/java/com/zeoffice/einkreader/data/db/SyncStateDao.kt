package com.zeoffice.einkreader.data.db

import androidx.room.*

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state")
    suspend fun getAll(): List<SyncStateEntity>

    @Query("SELECT * FROM sync_state WHERE bookKey = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE bookKey = :key")
    suspend fun delete(key: String)
}
