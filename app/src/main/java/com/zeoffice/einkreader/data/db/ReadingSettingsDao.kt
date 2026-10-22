package com.zeoffice.einkreader.data.db

import androidx.room.*

@Dao
interface ReadingSettingsDao {
    @Query("SELECT * FROM reading_settings WHERE id = 1")
    suspend fun get(): ReadingSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: ReadingSettingsEntity)

    @Update
    suspend fun update(s: ReadingSettingsEntity)
}
