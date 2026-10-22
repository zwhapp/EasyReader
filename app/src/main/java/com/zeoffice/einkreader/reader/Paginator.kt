package com.zeoffice.einkreader.reader

import android.graphics.Paint
import kotlin.math.max

/**
 * 排版分页引擎
 *
 * 给定章节纯文本 + 字体度量, 按内容区宽高分页, 记录每行的章节内字符偏移,
 * 以便点击命中检测 (用于内置词典取词)。
 */
data class LaidLine(
    val text: String,
    val yTop: Float,
    val baseline: Float,
    /** 该行首字符在章节全文中的偏移 */
    val charStart: Int
)

data class ReadPage(
    val chapterIndex: Int,
    val lines: List<LaidLine>,
    val startOffset: Int,
    val endOffset: Int
)

data class WordHit(
    val word: String,
    /** 命中词首字符在章节内的偏移 */
    val charIndex: Int,
    /** 点击字符在 word 内的下标 (用于生成候选词) */
    val clickIndexInRun: Int = 0
)

class Paginator(
    private val paint: Paint,
    private val contentWidth: Int,
    private val contentHeight: Int,
    private val lineHeightPx: Float,
    private val paragraphSpacingPx: Float
) {
    /**
     * 将单章文本分页
     */
    fun paginate(chapterIndex: Int, text: String): List<ReadPage> {
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val baselineOffset = (lineHeightPx - textHeight) / 2f - fm.ascent

        val pages = mutableListOf<ReadPage>()
        val currentLines = mutableListOf<LaidLine>()
        var y = 0f
        var offset = 0

        fun flushPage() {
            if (currentLines.isNotEmpty()) {
                val first = currentLines.first()
                val last = currentLines.last()
                pages.add(
                    ReadPage(
                        chapterIndex,
                        currentLines.toList(),
                        first.charStart,
                        last.charStart + last.text.length
                    )
                )
                currentLines.clear()
            }
            y = 0f
        }

        val paragraphs = text.split('\n')
        for (para in paragraphs) {
            if (para.isEmpty()) {
                if (y + lineHeightPx > contentHeight && currentLines.isNotEmpty()) flushPage()
                currentLines.add(LaidLine("", y, y + baselineOffset, offset))
                y += lineHeightPx
                offset += 1
                continue
            }
            val wrapped = wrapParagraph(para)
            for (li in wrapped.indices) {
                val lineText = wrapped[li]
                if (y + lineHeightPx > contentHeight && currentLines.isNotEmpty()) flushPage()
                currentLines.add(LaidLine(lineText, y, y + baselineOffset, offset))
                y += lineHeightPx
                offset += lineText.length
            }
            y += paragraphSpacingPx
            offset += 1
        }
        flushPage()
        return pages
    }

    private fun wrapParagraph(para: String): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var lineWidth = 0f
        var i = 0
        while (i < para.length) {
            val ch = para[i]
            val w = paint.measureText(ch.toString())
            if (lineWidth + w > contentWidth && line.isNotEmpty()) {
                lines.add(line.toString().trimEnd())
                line.setLength(0)
                lineWidth = 0f
                if (ch == ' ' || ch == '\u3000') {
                    i++
                    continue
                }
            }
            line.append(ch)
            lineWidth += w
            i++
        }
        if (line.isNotEmpty()) lines.add(line.toString().trimEnd())
        return lines
    }

    /** 命中检测: 返回点击位置处的词 */
    fun wordAt(page: ReadPage, x: Float, y: Float): WordHit? {
        for (line in page.lines) {
            val top = line.yTop
            if (y in top..(top + lineHeightPx)) {
                val text = line.text
                if (text.isEmpty()) return null
                var cum = 0f
                var idx = text.length
                for (k in text.indices) {
                    val cw = paint.measureText(text[k].toString())
                    if (x <= cum + cw / 2f) {
                        idx = k
                        break
                    }
                    cum += cw
                }
                if (idx > text.length) idx = text.length
                val token = extractToken(text, idx)
                return WordHit(token.text, line.charStart + token.start, idx - token.start)
            }
        }
        return null
    }

    private data class Token(val text: String, val start: Int)

    private fun extractToken(text: String, idx: Int): Token {
        val c = text.getOrNull(idx) ?: return Token("", idx)
        return if (isCjk(c)) {
            // 取点击处连续的中文字符片段 (上限 6, 便于匹配词语/成语)
            var s = idx
            while (s > 0 && isCjk(text[s - 1])) s--
            var e = idx
            while (e < text.length && isCjk(text[e]) && e - s < 6) e++
            Token(text.substring(s, e), s)
        } else {
            var s = idx
            while (s > 0 && isWordChar(text[s - 1])) s--
            var e = idx
            while (e < text.length && isWordChar(text[e])) e++
            Token(text.substring(s, e), s)
        }
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '-' || c == '’'

    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF ||       // CJK 统一表意文字
                cp in 0x3400..0x4DBF ||       // 扩展 A
                cp in 0xF900..0xFAFF ||       // 兼容汉字
                cp in 0x3000..0x303F ||       // CJK 符号与标点
                cp in 0xFF00..0xFFEF          // 全角字符
    }

    companion object {
        /** 根据章节内字符偏移定位页码 */
        fun pageIndexForOffset(pages: List<ReadPage>, offset: Int): Int {
            if (pages.isEmpty()) return 0
            for (i in pages.indices) {
                val p = pages[i]
                if (offset <= p.endOffset) return i
            }
            return pages.lastIndex
        }
    }
}
