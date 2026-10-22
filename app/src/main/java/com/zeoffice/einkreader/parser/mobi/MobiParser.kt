package com.zeoffice.einkreader.parser.mobi

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * MOBI / AZW / AZW3 (KF8) 解析器
 *
 * 三者同为 PDB (Palm Database) 容器:
 * - 文件头 78 字节, 记录表 @78 每项 8 字节 (4 偏移 + 1 属性 + 3 id)
 * - 记录 0 = PalmDOC Header + MOBI Header
 *   · compression @0   : 1=无压缩, 2=PalmDOC(LZ77), 17480=HUFF/CDIC
 *   · textRecordCount @8
 *   · textEncoding     : MOBI header 内 (1252=Latin1, 65001=UTF-8)
 *   · extraFlags @0xF2 : 每条记录末尾的附加数据标志 (需剥离)
 * - 正文 = 记录 1..textRecordCount (其后的记录是图片/资源, 不能当文本)
 */
class MobiParser(private val bookFormat: BookFormat = BookFormat.MOBI) : BookParser {

    override val format: BookFormat get() = bookFormat

    override fun parse(filePath: String): ParsedBook {
        val file = File(filePath)
        val bytes = file.readBytes()
        if (bytes.size < 80) throw Exception("文件太小，不是有效的 MOBI/AZW 书籍")

        // PDB: type @60 应为 "BOOK", creator @64 应为 "MOBI" (AZW3 为 "MOBI"+"MOBI")
        val type = String(bytes, 60, 4, Charsets.US_ASCII)
        val creator = String(bytes, 64, 4, Charsets.US_ASCII)
        if (type != "BOOK" && type != "TEXt" && creator != "MOBI") {
            throw Exception("不是有效的 MOBI/AZW 文件 (type=$type creator=$creator)")
        }

        // 记录表
        val recordCount = u16(bytes, 76)
        if (recordCount <= 0) throw Exception("MOBI 记录表为空")
        val offsets = IntArray(recordCount + 1)
        for (i in 0 until recordCount) {
            offsets[i] = u32(bytes, 78 + i * 8)
        }
        offsets[recordCount] = bytes.size

        fun record(i: Int): ByteArray =
            bytes.copyOfRange(offsets[i], offsets[i + 1])

        val rec0 = record(0)
        if (rec0.size < 16) throw Exception("MOBI 头损坏")

        val compression = u16(rec0, 0)
        // PalmDOC header (16B): @0 compression(2) @2 unused(2) @4 textLength(4)
        //                      @8 textRecordCount(2) @10 recordSize(2) @12 encryption(2) @14 unused(2)
        val textRecordCount = u16(rec0, 8)
        if (compression == 17480) throw Exception("暂不支持 HUFF/CDIC 压缩的 MOBI (多为 DRM 或旧版 Amazon 文件)")

        // MOBI Header (紧跟 PalmDOC header 之后)
        val hasMobi = rec0.size >= 24 && String(rec0, 16, 4, Charsets.US_ASCII) == "MOBI"
        val mobiHeaderLen = if (hasMobi) u32(rec0, 20) else 0
        val textEncoding = if (hasMobi && rec0.size >= 32) u32(rec0, 28) else 65001
        val charset = if (textEncoding == 1252) Charsets.ISO_8859_1 else Charsets.UTF_8
        val firstImageIndex = if (hasMobi && rec0.size >= 0x70) u32(rec0, 0x6C) else -1
        val extraFlags = if (hasMobi && mobiHeaderLen >= 0xE4 && rec0.size >= 0xF4) u16(rec0, 0xF2) else 0

        // 标题: fullNameOffset/fullNameLength @0x54/0x58 (相对 rec0)
        var title = ""
        if (hasMobi && rec0.size >= 0x5C) {
            try {
                val no = u32(rec0, 0x54)
                val nl = u32(rec0, 0x58)
                if (no > 0 && nl > 0 && no + nl <= rec0.size) {
                    title = String(rec0, no, nl, charset).trim()
                }
            } catch (_: Exception) { }
        }

        // EXTH: 提取作者 (type 100) 与封面记录号 (type 201)
        var author = ""
        var coverRecord = -1
        if (hasMobi && mobiHeaderLen >= 0xE4 && rec0.size > 16 + mobiHeaderLen + 8) {
            try {
                var p = 16 + mobiHeaderLen
                val magic = String(rec0, p, 4, Charsets.US_ASCII)
                if (magic == "EXTH") {
                    val exthLen = u32(rec0, p + 4)
                    val exthCount = u32(rec0, p + 8)
                    var q = p + 12
                    var n = 0
                    while (n < exthCount && q + 8 <= p + exthLen && q + 8 <= rec0.size) {
                        val t = u32(rec0, q)
                        val len = u32(rec0, q + 4)
                        if (len < 8 || q + len > rec0.size) break
                        val data = rec0.copyOfRange(q + 8, q + len)
                        when (t) {
                            100 -> author = String(data, charset).trim()
                            201 -> coverRecord = String(data, Charsets.US_ASCII).trim().toIntOrNull() ?: -1
                        }
                        q += len
                        n++
                    }
                }
            } catch (_: Exception) { }
        }

        // 解码正文记录 1..textRecordCount
        val body = StringBuilder()
        for (i in 1..textRecordCount.coerceAtMost(recordCount - 1)) {
            var rec = record(i)
            rec = stripTrailingEntries(rec, extraFlags)
            val decoded: ByteArray = when (compression) {
                1 -> rec                                   // 无压缩
                2 -> palmDocDecompress(rec)                // PalmDOC LZ77
                else -> rec
            }
            body.append(String(decoded, charset))
        }
        val text = stripHtml(body.toString())
        if (text.isBlank()) throw Exception("未能从文件中提取到文本 (可能为 DRM 加密书籍)")

        // 章节: 复用 TXT 的章节标记逻辑
        val lines = text.split("\n")
        val chapterStarts = lines.withIndex()
            .filter { (_, line) -> isChapterMarker(line) }
            .map { it.index to it.value.trim() }
        val chapters: List<ChapterContent> = if (chapterStarts.isEmpty()) {
            lines.chunked(2000).mapIndexed { i, chunk ->
                ChapterContent(i, "Part ${i + 1}", chunk.joinToString("\n"))
            }
        } else {
            chapterStarts.mapIndexed { idx, (start, t) ->
                val end = if (idx < chapterStarts.size - 1) chapterStarts[idx + 1].first else lines.size
                ChapterContent(idx, t, lines.subList(start + 1, end).joinToString("\n"), startLine = start)
            }
        }

        // 封面: firstImageIndex + coverRecord 处的记录即裸图 (JPG/PNG/GIF)
        val coverData = extractCover(records = ::record, recordCount = recordCount, firstImageIndex, coverRecord)

        return ParsedBook(
            title = title.ifBlank { file.nameWithoutExtension },
            author = author,
            chapters = chapters,
            format = bookFormat,
            coverData = coverData
        )
    }

    /** 提取内嵌封面图 (按图片魔数裁剪起点) */
    private fun extractCover(
        records: (Int) -> ByteArray,
        recordCount: Int,
        firstImageIndex: Int,
        coverRecord: Int
    ): ByteArray? {
        if (firstImageIndex < 0 || coverRecord < 0) return null
        val idx = firstImageIndex + coverRecord
        if (idx >= recordCount) return null
        return try {
            val data = records(idx)
            findImageStart(data) ?: return null
        } catch (_: Exception) { null }
    }

    private fun findImageStart(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        val start = when {
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> 0                       // JPEG
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> 0                       // PNG
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() -> 0                       // GIF
            else -> {
                // 记录前部可能有少量填充字节, 向后搜图片魔数
                var found = -1
                var i = 0
                while (i < data.size - 4 && i < 64) {
                    if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() ||
                        data[i] == 0x89.toByte() && data[i + 1] == 0x50.toByte()
                    ) { found = i; break }
                    i++
                }
                if (found >= 0) found else return null
            }
        }
        return if (start == 0) data else data.copyOfRange(start, data.size)
    }

    /** 剥离每条记录末尾的 trailing entries (extraFlags 高位标志, 从高位到低位依次剥) */
    private fun stripTrailingEntries(rec: ByteArray, flags: Int): ByteArray {
        if (flags == 0 || rec.isEmpty()) return rec
        var size = 0
        try {
            for (bit in 15 downTo 1) {
                if (flags and (1 shl bit) != 0) {
                    // 反向变长整数 (每字节低 7 位, 最高位=1 表示前面还有)
                    var p = rec.size - 1 - size
                    if (p < 0) return rec
                    var v = 0
                    var cnt = 0
                    while (p >= 0 && cnt < 4) {
                        val b = rec[p].toInt() and 0xFF
                        v = (v shl 7) or (b and 0x7F)
                        cnt++
                        if (b and 0x80 == 0) break
                        p--
                    }
                    size += v
                }
            }
            if (flags and 1 != 0) {
                // multibyte overlap: 末字节低 2 位 + 1 = 额外剥离字节数
                val last = rec[rec.size - 1 - size].toInt() and 0xFF
                size += (last and 0x3) + 1
            }
        } catch (_: Exception) { }
        if (size <= 0 || size >= rec.size) return rec
        return rec.copyOfRange(0, rec.size - size)
    }

    /** PalmDOC (LZ77) 解压: 标志字节每位对应后续 1 字节, 0=字面量, 1=回溯引用(2字节, 允许重叠) */
    private fun palmDocDecompress(rec: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rec.size * 8)
        val window = IntArray(4096) // 环形缓冲, 支持重叠引用
        var wpos = 0
        var i = 0
        while (i < rec.size) {
            val flags = rec[i].toInt() and 0xFF
            i++
            for (bit in 0..7) {
                if (i >= rec.size) break
                if (flags and (1 shl bit) != 0) {
                    if (i + 1 >= rec.size) break
                    val b1 = rec[i].toInt() and 0xFF
                    val b2 = rec[i + 1].toInt() and 0xFF
                    i += 2
                    val length = (b1 shr 4) + 3
                    var distance = (((b1 and 0x0F) shl 8) or b2) + 1
                    // 从环形缓冲按距离回溯 (允许与写入位置重叠, 逐字节复制)
                    var src = (wpos - distance + 4096) % 4096
                    for (j in 0 until length) {
                        val v = window[src]
                        out.write(v)
                        window[wpos] = v
                        wpos = (wpos + 1) % 4096
                        src = (src + 1) % 4096
                    }
                } else {
                    val v = rec[i].toInt() and 0xFF
                    out.write(v)
                    window[wpos] = v
                    wpos = (wpos + 1) % 4096
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        s = s.replace(Regex("(?i)<(mbp:pagebreak|hr)\\s*/?>"), "\n\n")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote|tr)>"), "\n")
        s = s.replace(Regex("(?i)<h([1-6])[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#160;", " ")
        return s.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** 章节标记判断 (与 TxtParser 一致) */
    private fun isChapterMarker(line: String): Boolean {
        val trimmed = line.trim()
        return (trimmed.matches(Regex("""第[一二三四五六七八九十百千零两\d]+[章节回卷集].*""")) ||
                trimmed.matches(Regex("""Chapter\s+\d+.*""", RegexOption.IGNORE_CASE)) ||
                trimmed.matches(Regex("""Part\s+\d+.*""", RegexOption.IGNORE_CASE))) &&
                trimmed.length < 60
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)
}
