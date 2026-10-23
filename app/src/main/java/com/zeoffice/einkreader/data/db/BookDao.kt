package com.zeoffice.einkreader.data.db

import androidx.room.*
import com.zeoffice.einkreader.domain.model.Book

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val fileSize: Long,
    val importedAt: Long,
    val progressPercent: Int = 0,
    val lastPage: Int = 0,
    val readingTimeSec: Long = 0L,
    val source: String,
    val remotePath: String = "",
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val chapterCount: Int = 0,
    val coverPath: String = "",
    /** 阅读进度最近更新时间 (毫秒) — 用于同步时检测本地进度是否改动 */
    val progressUpdatedAt: Long = 0L
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY importedAt DESC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Int): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int
}
