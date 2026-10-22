package com.zeoffice.einkreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书签 / 笔记
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId"])]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val charOffset: Int,
    val quote: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
