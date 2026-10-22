package com.zeoffice.einkreader.parser

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ParsedBook

/**
 * 统一解析器接口 — 每个格式实现一个
 */
interface BookParser {
    val format: BookFormat
    /** 从文件路径解析出统一内容模型 */
    fun parse(filePath: String): ParsedBook
}
