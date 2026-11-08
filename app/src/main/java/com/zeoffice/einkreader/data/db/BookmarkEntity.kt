package com.zeoffice.einkreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书签 / 笔记
 *
 * v10 起支持多端一致性：
 *  - uid：跨设备稳定 id（旧数据为空，运行时由 (章节,偏移,引文) 派生）；
 *  - stamp / updatedAt：HLC 逻辑时钟，用于同 id 的 LWW 归并；
 *  - deleted：软删除墓碑。删除必须留痕，否则同步时另一台设备会把删掉的书签重新带回来。
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
    val createdAt: Long = System.currentTimeMillis(),
    val uid: String = "",
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
    val stamp: String = ""
)
