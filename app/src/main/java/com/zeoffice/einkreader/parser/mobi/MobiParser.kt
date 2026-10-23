package com.zeoffice.einkreader.parser.mobi

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * MOBI / AZW / AZW3 (KF8) 解析器
 *
 * 三者同为 PDB (Palm Database) 容器, 记录 0 = PalmDOC 头(16B) + MOBI 头。
 * 关键偏移 (相对记录 0 字节):
 * - PalmDOC 头: compression @0(2)  unused @2(2)  textLength @4(4)
 *              textRecordCount @8(2)  recordSize @0xA(2)  encryption @0xC(2)
 * - MOBI 头 (紧跟 @0x10): length @0x14(4)  type @0x18(4)  textEncoding @0x1C(4)
 *              titleOffset @0x64(4)  titleLength @0x68(4)  firstResource @0x7C(4)
 *              huffOffset @0x70(4)  huffNumber @0x74(4)  mobiVersion @0x78(4)
 *              exthFlags @0x80(4)  traildataFlags @0xF2(2, 仅 headerLen>=0xE4 且 ver>=5)
 *              fullNameOffset @0x54(4)  fullNameLength @0x58(4)
 * 注: EXTH 区紧跟 MOBI 头之后(偏移 = 0x10 + mobiHeaderLen), 而非固定偏移。
 *
 * 压缩: 1=无压缩, 2=PalmDOC(LZ77), 0x4448(17480)=HUFF/CDIC(KF8 常用)。
 * 正文 = 记录 1..textRecordCount; 其后为图片/资源/索引, 不可当文本。
 *
 * 算法实现严格参照 KindleUnpack(mobi_uncompress.py) 的权威解压逻辑。
 */
class MobiParser(private val bookFormat: BookFormat = BookFormat.MOBI) : BookParser {

    override val format: BookFormat get() = bookFormat

    override fun parse(filePath: String): ParsedBook {
        val file = File(filePath)
        val bytes = file.readBytes()
        if (bytes.size < 80) throw Exception("文件太小，不是有效的 MOBI/AZW 书籍")

        // PDB 头: name@0(32) type@0x3C(4) creator@0x40(4)
        val type = String(bytes, 0x3C, 4, Charsets.US_ASCII)
        val creator = String(bytes, 0x40, 4, Charsets.US_ASCII)
        if (type != "BOOK" && type != "TEXt" && creator != "MOBI") {
            throw Exception("不是有效的 MOBI/AZW 文件 (type=$type creator=$creator)")
        }

        // 记录表: 记录数 @0x4C(2), 每项 8 字节(4 偏移 + 1 属性 + 3 id) @0x4E
        val recordCount = u16(bytes, 0x4C)
        if (recordCount <= 0) throw Exception("MOBI 记录表为空")
        val offsets = IntArray(recordCount + 1)
        for (i in 0 until recordCount) {
            offsets[i] = u32(bytes, 0x4E + i * 8)
        }
        offsets[recordCount] = bytes.size

        fun record(i: Int): ByteArray =
            if (i in 0 until recordCount) bytes.copyOfRange(offsets[i], offsets[i + 1]) else ByteArray(0)

        val rec0 = record(0)
        if (rec0.size < 0x10) throw Exception("MOBI 头损坏")

        val compression = u16(rec0, 0)
        val encryption = u16(rec0, 0xC)
        if (encryption != 0) throw Exception("该文件已加密(DRM)，无法解析")

        val textRecordCount = u16(rec0, 0x8)
        if (textRecordCount <= 0) throw Exception("未找到正文记录")

        // MOBI 头合法性
        val hasMobi = rec0.size >= 0x14 && String(rec0, 0x10, 4, Charsets.US_ASCII) == "MOBI"
        if (!hasMobi) throw Exception("缺少 MOBI 头")

        val mobiHeaderLen = u32(rec0, 0x14)
        val textEncoding = if (rec0.size >= 0x20) u32(rec0, 0x1C) else 65001
        val charset: Charset = when (textEncoding) {
            1252 -> runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
            else -> Charsets.UTF_8
        }

        // HUFF/CDIC 压缩表位置 (KF8 必备)
        val huffOffset = u32(rec0, 0x70)
        val huffNumber = u32(rec0, 0x74)

        // 标题 (MOBI 头内 titleOffset/titleLength, 相对记录 0)
        var title = ""
        try {
            val no = u32(rec0, 0x64)
            val nl = u32(rec0, 0x68)
            if (no > 0 && nl > 0 && no + nl <= rec0.size) {
                title = String(rec0, no, nl, charset).trim()
            }
        } catch (_: Exception) { }

        // 尾部数据标志 (仅 KF8 / mobiVersion >= 5 需要剥离)
        val mobiVersion = if (rec0.size >= 0x7C) u32(rec0, 0x78) else 0
        val traildataFlags = if (mobiHeaderLen >= 0xE4 && rec0.size >= 0xF4) u16(rec0, 0xF2) else 0
        var multibyte = 0
        var trailers = 0
        if (mobiHeaderLen >= 0xE4 && mobiVersion >= 5) {
            var f = traildataFlags
            multibyte = if ((f and 1) != 0) 1 else 0
            while (f > 1) {
                if ((f and 2) != 0) trailers++
                f = f ushr 1
            }
        }

        // EXTH: 作者(type 100) / 封面记录(type 201) / 缩略图(type 202)
        var author = ""
        var coverRecord = -1
        var thumbRecord = -1
        try {
            var p = 0x10 + mobiHeaderLen.toInt()
            if (p + 12 <= rec0.size && String(rec0, p, 4, Charsets.US_ASCII) == "EXTH") {
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
                        101 -> author = String(data, charset).trim()
                        201 -> coverRecord = String(data, Charsets.US_ASCII).trim().toIntOrNull() ?: -1
                        202 -> thumbRecord = String(data, Charsets.US_ASCII).trim().toIntOrNull() ?: -1
                    }
                    q += len
                    n++
                }
            }
        } catch (_: Exception) { }

        // 准备解压器
        val reader: RecordReader = when (compression) {
            1 -> UncompressedReader()
            2 -> PalmdocReader()
            0x4448 -> {
                require(huffOffset < recordCount && huffNumber > 0) { "HUFF 压缩表缺失或越界" }
                val huff = HuffReader().apply {
                    loadHuff(record(huffOffset.toInt()))
                    for (i in 1 until huffNumber.toInt()) {
                        loadCdic(record(huffOffset.toInt() + i))
                    }
                }
                huff
            }
            else -> throw Exception("不支持的压缩类型 0x%04X".format(compression))
        }

        // 解码正文记录 1..textRecordCount (KF8 需先剥离尾部数据)
        val body = ByteArrayOutputStream()
        val lastText = textRecordCount.coerceAtMost(recordCount - 1)
        for (i in 1..lastText) {
            var rec = record(i)
            if (trailers > 0 || multibyte != 0) rec = trimTrailingData(rec, trailers, multibyte)
            val decoded = reader.unpack(rec)
            body.write(decoded)
        }
        val text = stripHtml(String(body.toByteArray(), charset))
        if (text.isBlank()) throw Exception("未能从文件中提取到文本 (可能为 DRM 加密或空书)")

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

        // 封面: 优先 EXTH 201 绝对记录号; 否则尝试 firstResource 偏移; 再试 202 缩略图
        val firstResource = if (rec0.size >= 0x80) u32(rec0, 0x7C) else -1
        val coverData = extractCover(
            records = ::record, recordCount = recordCount,
            firstResource = firstResource.toInt(), coverRecord, thumbRecord
        )

        return ParsedBook(
            title = title.ifBlank { file.nameWithoutExtension },
            author = author,
            chapters = chapters,
            format = bookFormat,
            coverData = coverData
        )
    }

    /** 解压接口: 输入一条记录(已剥离尾部数据)的压缩字节, 输出原文 */
    private interface RecordReader {
        fun unpack(rec: ByteArray): ByteArray
    }

    private class UncompressedReader : RecordReader {
        override fun unpack(rec: ByteArray) = rec
    }

    /**
     * PalmDOC (LZ77) 解压 — 严格按字节取值区间解释:
     * 1..8 字面量游程; 0 与 9..127 字面量; 128..191 回溯引用(2 字节); 192..255 空格+字符。
     */
    private class PalmdocReader : RecordReader {
        override fun unpack(rec: ByteArray): ByteArray {
            val out = ByteArray(rec.size * 8 + 16)
            var wpos = 0
            var p = 0
            while (p < rec.size) {
                val c = rec[p].toInt() and 0xFF
                p++
                when {
                    c in 1..8 -> {
                        val end = (p + c).coerceAtMost(rec.size)
                        for (k in p until end) {
                            if (wpos < out.size) out[wpos++] = rec[k]
                        }
                        p = end
                    }
                    c < 128 -> {
                        if (wpos < out.size) out[wpos++] = c.toByte()
                    }
                    c >= 192 -> {
                        if (wpos < out.size) out[wpos++] = ' '.code.toByte()
                        if (wpos < out.size) out[wpos++] = (c xor 128).toByte()
                    }
                    else -> { // 128..191 回溯引用 (PalmDOC LZ77)
                        // 正确算法: 源地址 = 当前写位置 - 距离, 随写位置递增逐字节复制。
                        // 重叠(m<=n)与非重叠(m>n)均由此自然处理, 严禁再加 +k (会导致跳过字节)。
                        if (p < rec.size) {
                            val c2 = (c shl 8) or (rec[p].toInt() and 0xFF)
                            p++
                            val m = (c2 shr 3) and 0x07FF
                            val n = (c2 and 7) + 3
                            for (k in 0 until n) {
                                val src = wpos - m
                                if (src >= 0 && wpos < out.size) out[wpos++] = out[src]
                            }
                        }
                    }
                }
            }
            return out.copyOf(wpos)
        }
    }

    /**
     * HUFF/CDIC (压缩类型 0x4448, KF8/AZW3 常用) 解压 — 移植自 KindleUnpack HuffcdicReader。
     * 这是基于 Huffman 的位流解码, 与 PalmDOC 完全不同。
     */
    @OptIn(kotlin.ExperimentalUnsignedTypes::class)
    private class HuffReader : RecordReader {
        private val dict1 = Array(256) { Dict1(0, false, 0u) }
        private var mincode = UIntArray(33) { 0u }
        private var maxcode = UIntArray(33) { 0u }
        private var dictionary: Array<Any?> = emptyArray()

        private data class Dict1(val codelen: Int, val term: Boolean, val maxcode: UInt)

        // 嵌套非内部类无法访问外层 u16/u32, 此处提供本地 UInt 版辅助函数。
        private fun u16(b: ByteArray, off: Int): UInt =
            if (off + 1 < b.size) {
                (((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)).toUInt()
            } else 0u

        private fun u32(b: ByteArray, off: Int): UInt =
            if (off + 3 < b.size) {
                ((((b[off].toInt() and 0xFF).toULong() shl 24) or
                        ((b[off + 1].toInt() and 0xFF).toULong() shl 16) or
                        ((b[off + 2].toInt() and 0xFF).toULong() shl 8) or
                        (b[off + 3].toInt() and 0xFF).toULong())).toUInt()
            } else 0u

        fun loadHuff(huff: ByteArray) {
            require(huff.size >= 8 && String(huff, 0, 8, Charsets.US_ASCII) == "HUFF\u0000\u0000\u0000\u0018")
            val off1 = u32(huff, 8).toInt()
            val off2 = u32(huff, 12).toInt()
            for (i in 0 until 256) {
                val v = u32(huff, off1 + i * 4)
                val codelen = (v and 0x1Fu).toInt()
                val term = (v and 0x80u) != 0u
                val m = v shr 8
                val mc = if (codelen == 0) 0u else (((m + 1u) shl (32 - codelen)) - 1u)
                dict1[i] = Dict1(codelen, term, mc)
            }
            val d2 = UIntArray(64) { u32(huff, off2 + it * 4) }
            for (k in 0..32) {
                val eMin = if (k == 0) 0u else d2[(k - 1) * 2]
                val eMax = if (k == 0) 0u else d2[(k - 1) * 2 + 1]
                mincode[k] = if (k == 0) 0u else (eMin shl (32 - k))
                maxcode[k] = if (k == 0) 0u else (((eMax + 1u) shl (32 - k)) - 1u)
            }
        }

        fun loadCdic(cdic: ByteArray) {
            require(cdic.size >= 8 && String(cdic, 0, 8, Charsets.US_ASCII) == "CDIC\u0000\u0000\u0000\u0010")
            val phrases = u32(cdic, 8).toInt()
            val bits = u32(cdic, 12).toInt()
            val n = minOf(1 shl bits, phrases - dictionary.size)
            val offsets = IntArray(n) { u16(cdic, 16 + it * 2).toInt() }
            val newDict = arrayOfNulls<Any?>(dictionary.size + n)
            for (j in dictionary.indices) newDict[j] = dictionary[j]
            for (j in 0 until n) {
                val off = offsets[j]
                val blen = u16(cdic, 16 + off).toInt()
                val slice = cdic.copyOfRange(18 + off, 18 + off + (blen and 0x7FFF))
                val flag = (blen and 0x8000) != 0
                newDict[dictionary.size + j] = if (flag) Pair(slice, 1) else Pair(slice, 0)
            }
            dictionary = newDict
        }

        override fun unpack(data: ByteArray): ByteArray {
            val s = ByteArrayOutputStream()
            val buf = ByteArray(data.size + 8) { if (it < data.size) data[it] else 0 }
            var pos = 0
            var x = readQ(buf, pos)
            var n = 32
            var bitsleft = data.size * 8
            var guard = 0
            while (guard++ < data.size * 8 + 1024) {
                if (n <= 0) {
                    pos += 4
                    x = readQ(buf, pos)
                    n += 32
                }
                val code = (x shr n) and 0xFFFFFFFFu
                val top = (code shr 24).toInt() and 0xFF
                var codelen = dict1[top].codelen
                var term = dict1[top].term
                var mc = dict1[top].maxcode
                if (!term) {
                    while (codelen <= 32 && code < mincode[codelen]) codelen++
                    mc = if (codelen <= 32) maxcode[codelen] else 0u
                }
                n -= codelen
                bitsleft -= codelen
                if (bitsleft < 0 || codelen > 32) break
                val r = ((mc - code) shr (32 - codelen)).toInt()
                if (r < 0 || r >= dictionary.size) break
                val entry = dictionary[r] as? Pair<ByteArray, Int>
                val slice = if (entry != null && entry.second == 0) {
                    dictionary[r] = null
                    val dec = unpack(entry.first)
                    dictionary[r] = Pair(dec, 1)
                    dec
                } else if (entry != null) {
                    entry.first
                } else break
                s.write(slice)
            }
            return s.toByteArray()
        }

        private fun readQ(b: ByteArray, pos: Int): ULong {
            var v = 0uL
            for (i in 0..7) {
                val bb = if (pos + i < b.size) b[pos + i].toUByte().toULong() else 0uL
                v = (v shl 8) or bb
            }
            return v
        }
    }

    /** 剥离 KF8 每条记录末尾的 trailing data (extraFlags 高位标志) */
    private fun trimTrailingData(data: ByteArray, trailers: Int, multibyte: Int): ByteArray {
        if (trailers <= 0 && multibyte == 0) return data
        var d = data
        for (i in 0 until trailers) {
            if (d.size < 4) break
            val last4 = d.copyOfRange(d.size - 4, d.size)
            var num = 0
            for (v in last4) {
                val ub = v.toUByte().toUInt()
                if ((ub and 0x80u) != 0u) num = 0
                num = (num shl 7) or (ub and 0x7Fu).toInt()
            }
            if (num <= 0 || num >= d.size) break
            d = d.copyOfRange(0, d.size - num)
        }
        if (multibyte != 0 && d.isNotEmpty()) {
            val last = (d[d.size - 1].toUByte().toUInt() and 3u).toInt()
            val cut = last + 1
            if (cut <= d.size) d = d.copyOfRange(0, d.size - cut)
        }
        return d
    }

    /** 提取内嵌封面图 (按图片魔数裁剪起点) */
    private fun extractCover(
        records: (Int) -> ByteArray,
        recordCount: Int,
        firstResource: Int,
        coverRecord: Int,
        thumbRecord: Int
    ): ByteArray? {
        var idx = coverRecord
        // KF8 中 EXTH 201 多为相对 firstResource 的偏移; 若作为绝对记录号无效则尝试偏移
        if (idx !in 0 until recordCount && firstResource > 0) {
            val alt = firstResource + coverRecord
            if (alt in 0 until recordCount) idx = alt
        }
        if (idx in 0 until recordCount) {
            findImage(records(idx))?.let { return it }
        }
        // 退而求其次: 缩略图记录
        if (thumbRecord in 0 until recordCount) {
            findImage(records(thumbRecord))?.let { return it }
        }
        return null
    }

    private fun findImage(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        val start = when {
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> 0                       // JPEG
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> 0                       // PNG
            data[0] == 0x47.toByte() && data[1] == 0x49.toByte() -> 0                       // GIF
            else -> {
                var found = -1
                var i = 0
                while (i < data.size - 4 && i < 256) {
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
        if (off + 1 < b.size) ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF) else 0

    private fun u32(b: ByteArray, off: Int): Int =
        if (off + 3 < b.size) {
            ((b[off].toInt() and 0xFF) shl 24) or
                    ((b[off + 1].toInt() and 0xFF) shl 16) or
                    ((b[off + 2].toInt() and 0xFF) shl 8) or
                    (b[off + 3].toInt() and 0xFF)
        } else 0
}
