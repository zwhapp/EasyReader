package com.zeoffice.einkreader.domain.model

/**
 * 书籍实体 (对应 Room 数据库表 books)
 */
data class Book(
    val id: Int = 0,
    val title: String,
    val author: String = "",
    val format: BookFormat,
    /** 本地存储文件路径 (SAF Uri 或绝对路径) */
    val filePath: String,
    val fileSize: Long = 0,
    /** 导入时间戳 */
    val importedAt: Long = System.currentTimeMillis(),
    /** 阅读进度 (百分比 0-100) */
    val progressPercent: Int = 0,
    /** 上次阅读页码/章节偏移 */
    val lastPage: Int = 0,
    /** 阅读时长 (秒) */
    val readingTimeSec: Long = 0,
    /** 来源标记: local / webdav / github */
    val source: String = "local",
    /** WebDAV 远程路径 (如适用) */
    val remotePath: String = ""
)

/**
 * 章节实体 (用于 EPUB/MOBI/AZW3 含目录的书籍)
 */
data class Chapter(
    val id: Int = 0,
    val bookId: Int,
    val orderIndex: Int,
    val title: String,
    /** 解析后的起始偏移(字节或行) */
    val startOffset: Long = 0,
    val endOffset: Long = 0
)
