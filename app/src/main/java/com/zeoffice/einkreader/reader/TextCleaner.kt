package com.zeoffice.einkreader.reader

/**
 * 正文智能整理（纯 Kotlin，无 Android 依赖，便于 JVM 单元测试）。
 *
 * 电子书（尤其 TXT / 网页转出的 EPUB）常见问题：
 *  - Windows CRLF、行尾空格、行首手工缩进（全角空格）、行内连续空格、制表符
 *  - 连续 3~10 个空行、章节之间一堆空行
 *  - TXT 常见的「每行硬换行」：一个自然段被拆成十几行，若直接按行分段，
 *    每段都会被加段间距与首行缩进，读起来支离破碎
 *  - BOM / 零宽字符等不可见控制符被当成正文绘制
 *
 * 处理目标：统一换行 → 逐行去多余空白 → 合并连续空行 → 智能合并软换行为自然段
 * → 输出以单个 '\n' 分隔段落的干净文本。段间距由排版参数提供，不再依赖空行。
 *
 * 设计取舍：诗歌、歌词、代码类文本不适合软换行合并，设置里可关闭「正文智能整理」。
 */
object TextCleaner {

    /** 句末标点：出现即视为一个自然段结束，不再与下一行合并 */
    private val SENTENCE_END = charArrayOf(
        '。', '！', '？', '；', '：', '…', '—', '.', '!', '?', ';', ':',
        '”', '’', '」', '』', '）', ')', '】', '》', '〉', '"', '\'', '~'
    )

    private val MULTI_SPACE = Regex(" {2,}")
    private val CHAPTER_MARK = Regex(
        """^(第[一二三四五六七八九十百千零两〇\d]+[章节回卷集部篇].{0,40}|Chapter\s+\d+.{0,40}|Part\s+\d+.{0,40}|序|序言|楔子|引子|后记|尾声|番外|附录|前言|目录)$""",
        RegexOption.IGNORE_CASE
    )
    private val ASCII_WORD = Regex("[A-Za-z0-9]")

    /**
     * @param raw 原始章节文本
     * @param smartJoin 是否合并软换行（关闭时只做空白/空行清理，保留原有每行分段）
     */
    fun clean(raw: String, smartJoin: Boolean = true): String {
        if (raw.isEmpty()) return ""
        val normalized = normalize(raw)
        val lines = normalized.split('\n')

        // 1) 逐行清理：去首尾空白、压缩行内连续空格
        val trimmed = ArrayList<String>(lines.size)
        for (l in lines) {
            val t = MULTI_SPACE.replace(l.trim(), " ")
            trimmed.add(t)
        }

        // 2) 收集非空行，并记录「前面是否出现过空行」作为自然段边界
        val segments = ArrayList<Pair<String, Boolean>>()
        var pendingBlank = false
        for (t in trimmed) {
            if (t.isEmpty()) {
                if (segments.isNotEmpty() || pendingBlank) pendingBlank = true
                continue
            }
            segments.add(t to pendingBlank)
            pendingBlank = false
        }
        if (segments.isEmpty()) return ""

        // 3) 文档级判断：有的 TXT 是「每行一个自然段」（行较长且多以句末标点收尾），
        //    这种格式每行已经是完整段落，再合并会把整章粘成一大段；
        //    其余是「硬换行」格式（短行、行尾多为逗号或无标点），按空行分段合并。
        //    注意：绝不能按「句末标点」切段——中文硬换行里一句说完，下一行仍属同一段。
        val mergeLines = smartJoin && !isPerLineParagraph(segments.map { it.first })

        // 4) 组装自然段
        val paragraphs = ArrayList<String>()
        val buf = StringBuilder()
        var prevIsTitle = false

        fun flush() {
            if (buf.isNotEmpty()) {
                paragraphs.add(buf.toString())
                buf.setLength(0)
            }
        }

        for ((line, hadBlank) in segments) {
            val isTitle = isChapterTitleLike(line)
            when {
                isTitle -> {
                    flush()
                    paragraphs.add(line)
                    prevIsTitle = true
                }
                hadBlank -> {
                    // 原文用空行明确分段：尊重原意，不合并
                    flush()
                    buf.append(line)
                    prevIsTitle = false
                }
                mergeLines && buf.isNotEmpty() && !prevIsTitle -> {
                    // 软换行：拼到同一段；英文单词之间补空格
                    if (needsSpaceBetween(buf[buf.length - 1], line[0])) buf.append(' ')
                    buf.append(line)
                    prevIsTitle = false
                }
                else -> {
                    flush()
                    buf.append(line)
                    prevIsTitle = false
                }
            }
        }
        flush()
        return paragraphs.joinToString("\n")
    }

    /** 「每行一段」格式：多数行较长且以句末标点收尾 */
    private fun isPerLineParagraph(lines: List<String>): Boolean {
        if (lines.size < 3) return false
        val avg = lines.sumOf { it.length }.toDouble() / lines.size
        val endRatio = lines.count { endsSentence(it) }.toDouble() / lines.size
        return avg >= 28 && endRatio >= 0.6
    }

    /** 只做空白与空行清理，不改变分段（供「关闭智能合并」或外部预览使用） */
    fun cleanWhitespace(raw: String): String {
        if (raw.isEmpty()) return ""
        val lines = normalize(raw).split('\n')
        val out = ArrayList<String>()
        var lastBlank = false
        for (l in lines) {
            val t = MULTI_SPACE.replace(l.trim(), " ")
            if (t.isEmpty()) {
                if (out.isNotEmpty()) lastBlank = true
                continue
            }
            if (lastBlank && out.isNotEmpty()) out.add("")
            out.add(t)
            lastBlank = false
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out.joinToString("\n")
    }

    /** 统一换行符，去掉 BOM / 零宽字符 / 不间断空格，把全角空格与制表符当归空白 */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when (c) {
                '\r' -> {
                    sb.append('\n')
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                '\u3000', '\t', '\u00A0' -> sb.append(' ')
                '\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun endsSentence(line: String): Boolean {
        val c = line.lastOrNull() ?: return true
        return SENTENCE_END.contains(c)
    }

    private fun needsSpaceBetween(a: Char, b: Char): Boolean =
        ASCII_WORD.matches(a.toString()) && ASCII_WORD.matches(b.toString())

    /** 章节 / 标题行：独立成段，不参与软换行合并 */
    fun isChapterTitleLike(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && t.length < 60 && CHAPTER_MARK.matches(t)
    }
}
