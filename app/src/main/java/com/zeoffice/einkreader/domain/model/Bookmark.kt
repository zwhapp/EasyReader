package com.zeoffice.einkreader.domain.model

data class Bookmark(
    val id: Int = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val charOffset: Int,
    val quote: String,
    val note: String = "",
    val createdAt: Long = 0
)
