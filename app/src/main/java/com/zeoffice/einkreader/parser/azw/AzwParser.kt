package com.zeoffice.einkreader.parser.azw

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import com.zeoffice.einkreader.parser.mobi.MobiParser
import java.io.File

/**
 * AZW / AZW3 (KF8) 解析器
 *
 * 两者本质都是 PDB/MOBI 容器 (type="BOOK", creator="MOBI"):
 * - .azw  = 旧版 MOBI (KF6/KF7)
 * - .azw3 = KF8, 记录结构相同 (仅 extraFlags / skeleton 差异)
 * 统一交由 MobiParser 解码 (含 trailing entries 剥离, KF8 必需)。
 *
 * 注: AZW3 内部没有"内嵌 EPUB", 此前基于 zip 签名的提取逻辑是错误的, 已移除。
 */
class AzwParser(private val asFormat: BookFormat = BookFormat.AZW3) : BookParser {

    override val format: BookFormat get() = formatField
    private val formatField = asFormat

    override fun parse(filePath: String): ParsedBook {
        // 扩展名与文件真实格式可能不符: 探测 PDB 魔数决定走哪条路
        val file = File(filePath)
        val head = file.inputStream().use { ins ->
            val buf = ByteArray(68)
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
        val looksPdb = head.size >= 68 &&
                String(head, 60, 4, Charsets.US_ASCII) == "BOOK"

        return if (looksPdb) {
            MobiParser(formatField).parse(filePath)
        } else {
            // 个别 .azw 实为 EPUB 换壳
            com.zeoffice.einkreader.parser.epub.EpubParser().parse(filePath)
        }
    }
}
