package com.zeoffice.einkreader.domain.model

data class Bookmark(
    val id: Int = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val charOffset: Int,
    val quote: String,
    val note: String = "",
    // 手动新增书签必须带时间戳：旧默认 0 会让「按时间倒序」和同步冲突裁决全部失效
    val createdAt: Long = System.currentTimeMillis()
)
