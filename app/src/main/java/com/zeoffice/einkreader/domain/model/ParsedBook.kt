package com.zeoffice.einkreader.domain.model

/**
 * 统一章节内容模型 — 所有解析器最终输出
 */
data class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<ChapterContent>,
    val format: BookFormat,
    val coverData: ByteArray? = null
)

data class ChapterContent(
    val orderIndex: Int,
    val title: String,
    /** 纯文本内容 (从源格式抽取) */
    val text: String,
    /** 图片相对路径 (EPUB/MOBI 内嵌图) */
    val imageRef: String? = null,
    val startLine: Int = 0
)
