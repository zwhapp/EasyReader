package com.zeoffice.einkreader.reader

import android.graphics.Paint

/**
 * 排版分页引擎
 *
 * 给定章节纯文本 + 字体度量, 按内容区宽高分页, 记录每行的章节内字符偏移,
 * 以便点击命中检测 (用于内置词典取词)。
 *
 * 关键约定: 每行在排版时一次性算出每个字符的绘制起点 charXs (相对内容区左上角),
 * 绘制 (ReaderView) 与命中检测 (wordAt) 共用这一份数据; 两端对齐追加的字距
 * 已经计入 charXs。这样"看到的位置"与"点到的字"永远一致。
 */
data class LaidLine(
    val text: String,
    val yTop: Float,
    val baseline: Float,
    /** 该行首字符在章节全文中的偏移 */
    val charStart: Int,
    /** 每个字符的绘制起点 x; size = text.length + 1, 末位为行尾 x */
    val charXs: List<Float>
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
    private val paragraphSpacingPx: Float,
    private val justify: Boolean = true
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

        fun addLine(t: String, consumed: Int) {
            currentLines.add(LaidLine(t, y, y + baselineOffset, offset, layoutLine(t)))
            // consumed 是这行在章节原文里实际吃掉的字符数: 被 trimEnd 丢掉的尾空格、
            // 换行时被跳过的行首空格都要算进去, 否则后续 charStart 会持续偏小。
            offset += consumed
            y += lineHeightPx
        }

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
                addLine("", 1) // 空行对应原文的一个 '\n'
                continue
            }
            val wrapped = wrapParagraph(para)
            for (wl in wrapped) {
                if (y + lineHeightPx > contentHeight && currentLines.isNotEmpty()) flushPage()
                addLine(wl.text, wl.consumed)
            }
            y += paragraphSpacingPx
            offset += 1 // 段落分隔的 '\n'
        }
        flushPage()
        return pages
    }

    private data class WrappedLine(val text: String, val consumed: Int)

    private fun wrapParagraph(para: String): List<WrappedLine> {
        val lines = mutableListOf<WrappedLine>()
        val line = StringBuilder()
        var lineWidth = 0f
        var i = 0
        while (i < para.length) {
            val ch = para[i]
            val w = paint.measureText(ch.toString())
            if (lineWidth + w > contentWidth && line.isNotEmpty()) {
                val raw = line.toString()
                var consumed = raw.length
                line.setLength(0)
                lineWidth = 0f
                val skipSpace = ch == ' ' || ch == '\u3000'
                if (skipSpace) {
                    // 行首空格不绘制, 但它在原文里存在, consumed 里补上 1
                    i++
                    consumed++
                }
                lines.add(WrappedLine(raw.trimEnd(), consumed))
                if (skipSpace) continue
            }
            line.append(ch)
            lineWidth += w
            i++
        }
        if (line.isNotEmpty()) {
            val raw = line.toString()
            lines.add(WrappedLine(raw.trimEnd(), raw.length))
        }
        return lines
    }

    /**
     * 计算一行每个字符的绘制起点。两端对齐的额外字距在此一次性定下来,
     * 绘制与命中检测都只读这份结果, 不再各自用 measureText 累加。
     */
    private fun layoutLine(text: String): List<Float> {
        if (text.isEmpty()) return listOf(0f)
        val widths = FloatArray(text.length) { paint.measureText(text[it].toString()) }
        var natural = 0f
        for (w in widths) natural += w
        val charW = paint.measureText("国").coerceAtLeast(1f)
        val gaps = text.length - 1
        val slack = contentWidth - natural
        // 与原两端对齐规则一致: 仅"自然换行"的整行拉伸, 段落末行/短行保持左对齐。
        // slack 为负时不再挤压 (旧实现会产生负字距, 让字符叠在一起)。
        val per = if (justify && gaps > 0 && slack >= 0f && slack <= charW) slack / gaps else 0f
        val xs = ArrayList<Float>(text.length + 1)
        var x = 0f
        for (i in text.indices) {
            xs.add(x)
            x += widths[i] + per
        }
        xs.add(x)
        return xs
    }

    /** 命中检测: 返回点击位置处的词片段 */
    fun wordAt(page: ReadPage, x: Float, y: Float): WordHit? {
        val line = page.lines.firstOrNull { y >= it.yTop && y <= it.yTop + lineHeightPx }
            ?: return null
        val text = line.text
        if (text.isEmpty()) return null
        val idx = TextHit.indexOfX(line.charXs, x)
        val token = extractToken(text, idx)
        if (token.text.isEmpty()) return null
        return WordHit(token.text, line.charStart + token.start, idx - token.start)
    }

    private data class Token(val text: String, val start: Int)

    private fun extractToken(text: String, idx: Int): Token {
        val c = text.getOrNull(idx) ?: return Token("", idx)
        return if (isHan(c)) {
            // 以点选字为中心取一段连续汉字窗口: 左最多 5 字, 窗口总长最多 12。
            // 旧实现把 s 一路退到连续汉字串的开头且 e 从 idx 起算, 导致 word 变成
            // "从行首到点击处"的长串, 点得越靠后越长, 取词几乎固定命中开头的字。
            var s = idx
            while (s > 0 && isHan(text[s - 1]) && idx - s < 5) s--
            var e = idx + 1
            while (e < text.length && isHan(text[e]) && e - s < 12) e++
            Token(text.substring(s, e), s)
        } else if (isWordChar(c)) {
            var s = idx
            while (s > 0 && isWordChar(text[s - 1])) s--
            var e = idx
            while (e < text.length && isWordChar(text[e])) e++
            Token(text.substring(s, e), s)
        } else {
            Token("", idx) // 标点 / 空白不取词
        }
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '-' || c == '’'

    /** 仅表意汉字参与中文取词窗口; 全角标点作为边界, 不再被当成汉字 */
    private fun isHan(c: Char): Boolean {
        val cp = c.code
        return cp in 0x4E00..0x9FFF ||       // CJK 统一表意文字
                cp in 0x3400..0x4DBF ||       // 扩展 A
                cp in 0xF900..0xFAFF          // 兼容汉字
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
