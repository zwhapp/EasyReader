package com.zeoffice.einkreader.parser.txt

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 解析器
 * - 编码：BOM 判别 UTF-8 / UTF-16LE / UTF-16BE；无 BOM 时用严格 UTF-8 解码试探，
 *   失败（非法字节）回退 GB18030（兼容 GBK / GB2312），并支持无 BOM 的 UTF-16
 * - 换行：统一 CRLF / CR
 * - 章节：第一个章节标记之前的内容不再丢弃，作为「前言」章保留
 */
class TxtParser : BookParser {
    override val format = BookFormat.TXT

    override fun parse(filePath: String): ParsedBook {
        val file = File(filePath)
        val text = detectAndRead(file)
        val lines = text.split(Regex("\r?\n"))
        val chapterStarts = lines.withIndex()
            .filter { (_, line) -> isChapterMarker(line) }
            .map { it.index to it.value.trim() }

        if (chapterStarts.isEmpty()) {
            // 无章节标记: 整本按 5000 行分卷（正文整理在阅读前统一进行）
            val chunks = lines.chunked(5000)
            return ParsedBook(
                title = file.nameWithoutExtension,
                author = "",
                format = BookFormat.TXT,
                chapters = chunks.mapIndexed { i, chunk ->
                    ChapterContent(i, "Part ${i + 1}", chunk.joinToString("\n"))
                }.ifEmpty { listOf(ChapterContent(0, "正文", "")) }
            )
        }

        val chapters = mutableListOf<ChapterContent>()
        // 书名页 / 序言 / 卷首题记：旧实现直接丢弃这部分内容
        val firstStart = chapterStarts.first().first
        if (firstStart > 0) {
            val head = lines.subList(0, firstStart).joinToString("\n")
            if (head.isNotBlank()) chapters += ChapterContent(0, "前言", head)
        }
        chapterStarts.forEachIndexed { idx, (start, title) ->
            val end = if (idx < chapterStarts.size - 1) chapterStarts[idx + 1].first else lines.size
            val body = lines.subList(start + 1, end).joinToString("\n")
            if (body.isBlank()) return@forEachIndexed // 相邻章节标记产生的空章不进目录
            chapters += ChapterContent(chapters.size, title, body, startLine = start)
        }
        if (chapters.isEmpty()) chapters += ChapterContent(0, "正文", text)
        return ParsedBook(file.nameWithoutExtension, "", chapters, BookFormat.TXT)
    }

    private fun isChapterMarker(line: String): Boolean {
        val trimmed = line.trim()
        return (trimmed.matches(Regex("""第[一二三四五六七八九十百千零两\d]+[章节回卷集].*""")) ||
                trimmed.matches(Regex("""Chapter\s+\d+.*""", RegexOption.IGNORE_CASE)) ||
                trimmed.matches(Regex("""Part\s+\d+.*""", RegexOption.IGNORE_CASE))) &&
                trimmed.length < 60
    }

    private fun detectAndRead(file: File): String {
        val head = ByteArray(4)
        java.io.RandomAccessFile(file, "r").use { raf -> raf.read(head) }
        val bytes = file.readBytes()
        return when {
            head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            // 无 BOM 的 UTF-16：ASCII 文本会呈现 00 xx 00 xx / xx 00 xx 00 交替
            bytes.size >= 4 && head[0] == 0.toByte() && head[2] == 0.toByte() && head[1] != 0.toByte() ->
                String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
            bytes.size >= 4 && head[1] == 0.toByte() && head[3] == 0.toByte() && head[0] != 0.toByte() ->
                String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
            else -> readUtf8OrGbk(bytes)
        }
    }

    /**
     * Kotlin 的 readText(UTF_8) 默认 REPLACE，非法字节会被替换成 U+FFFD 而不抛异常，
     * 导致旧实现的 GBK catch 分支永远走不到、中文 TXT 全是乱码。
     * 这里改用 REPORT 模式的严格解码，失败才回退 GB18030。
     */
    private fun readUtf8OrGbk(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }
}
