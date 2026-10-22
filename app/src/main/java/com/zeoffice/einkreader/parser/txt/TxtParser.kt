package com.zeoffice.einkreader.parser.txt

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * TXT 解析器
 * - 支持 UTF-8 / UTF-16 / GBK 自动检测
 * - 按空行分章节 (可选) 或纯流式
 */
class TxtParser : BookParser {
    override val format = BookFormat.TXT

    override fun parse(filePath: String): ParsedBook {
        val file = File(filePath)
        // 简单编码检测: BOM / 首字节
        val text = detectAndRead(file)
        val lines = text.split("\n")
        // 启发式: 找 "第X章/第X节/Chapter X" 模式分章
        val chapterStarts = lines.withIndex()
            .filter { (_, line) -> isChapterMarker(line) }
            .map { it.index to it.value.trim() }
        if (chapterStarts.isEmpty()) {
            // 无章节标记: 整本一章, 按 5000 行截断
            val chunks = lines.chunked(5000)
            return ParsedBook(
                title = file.nameWithoutExtension,
                author = "",
                format = BookFormat.TXT,
                chapters = chunks.mapIndexed { i, chunk ->
                    ChapterContent(
                        orderIndex = i,
                        title = "Part ${i + 1}",
                        text = chunk.joinToString("\n")
                    )
                }
            )
        }
        val chapters = mutableListOf<ChapterContent>()
        chapterStarts.forEachIndexed { idx, (start, title) ->
            val end = if (idx < chapterStarts.size - 1) chapterStarts[idx + 1].first else lines.size
            val body = lines.subList(start + 1, end).joinToString("\n")
            chapters += ChapterContent(idx, title, body, startLine = start)
        }
        return ParsedBook(file.nameWithoutExtension, "", chapters, BookFormat.TXT)
    }

    private fun isChapterMarker(line: String): Boolean {
        val trimmed = line.trim()
        // matches() 为全串匹配, 故允许标题行带尾缀 (如 "第一章 测试开篇")
        return (trimmed.matches(Regex("""第[一二三四五六七八九十百千零两\d]+[章节回卷集].*""")) ||
                trimmed.matches(Regex("""Chapter\s+\d+.*""", RegexOption.IGNORE_CASE)) ||
                trimmed.matches(Regex("""Part\s+\d+.*""", RegexOption.IGNORE_CASE))) &&
                trimmed.length < 60
    }

    private fun detectAndRead(file: File): String {
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(0)
            val buf = ByteArray(4)
            val n = raf.read(buf)
            if (n >= 2) {
                when {
                    buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() ->
                        File(file.absolutePath).readText(Charsets.UTF_8)
                    buf[0] == 0xFE.toByte() && buf[1] == 0xFF.toByte() ->
                        File(file.absolutePath).readText(Charsets.UTF_16BE)
                    buf[0] == 0xFF.toByte() && buf[1] == 0xFE.toByte() ->
                        File(file.absolutePath).readText(Charsets.UTF_16LE)
                    else -> try {
                        File(file.absolutePath).readText(Charsets.UTF_8)
                    } catch (e: Exception) {
                        File(file.absolutePath).readText(Charset.forName("GBK"))
                    }
                }
            } else ""
        }
    }
}
