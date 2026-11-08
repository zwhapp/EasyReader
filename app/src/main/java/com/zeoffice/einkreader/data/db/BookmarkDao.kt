package com.zeoffice.einkreader.data.db

import androidx.room.*

@Dao
interface BookmarkDao {
    /** UI 展示用：只看活书签 */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND deleted = 0 ORDER BY createdAt DESC")
    suspend fun getByBook(bookId: Int): List<BookmarkEntity>

    /** 同步用：含墓碑，删除状态才能传播到其他设备 */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getAllByBook(bookId: Int): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: BookmarkEntity): Long

    /** 软删除并写下 HLC 墓碑（不再物理删除） */
    @Query("UPDATE bookmarks SET deleted = 1, updatedAt = :at, stamp = :stamp WHERE id = :id")
    suspend fun markDeleted(id: Int, at: Long, stamp: String)

    @Delete
    suspend fun delete(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Int)
}
