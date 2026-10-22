package com.zeoffice.einkreader.data.db

import androidx.room.*

@Dao
interface CloudAccountDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY id ASC")
    suspend fun getAll(): List<CloudAccountEntity>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id")
    suspend fun getById(id: Int): CloudAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: CloudAccountEntity): Long

    @Update
    suspend fun update(a: CloudAccountEntity)

    @Delete
    suspend fun delete(a: CloudAccountEntity)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun deleteById(id: Int)
}
