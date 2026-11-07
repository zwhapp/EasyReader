package com.zeoffice.einkreader.reader

import org.junit.Assert.*
import org.junit.Test

class TextCleanerTest {

    @Test
    fun `CRLF 行尾空格 行首全角空格与制表符被清理`() {
        val raw = "\uFEFF\u3000\u3000第一章  开端   \r\n\t正文内容  \r\n  \r\n"
        val out = TextCleaner.clean(raw, smartJoin = false)
        assertFalse(out.contains('\r'))
        assertFalse(out.contains('\u3000'))
        assertFalse(out.contains('\t'))
        assertFalse(out.contains('\uFEFF'))
        assertTrue(out.startsWith("第一章 开端"))
    }

    @Test
    fun `连续多余空行被删除且输出不残留空行`() {
        val raw = "第一段。\n\n\n\n\n\n第二段。\n\n\n第三段。"
        val out = TextCleaner.clean(raw, smartJoin = false)
        assertFalse(out.contains("\n\n"))
        assertEquals(3, out.split('\n').size)
    }

    @Test
    fun `中文硬换行合并为一个自然段 句末标点不误切段`() {
        val raw = "他抬头看向远处的山峦，\n云层很低，\n像是要落下雨来。\n她没有说话。"
        val out = TextCleaner.clean(raw)
        // 硬换行文本里一句说完，下一行仍属同一段；只有原文空行才是段落边界
        assertEquals(1, out.split('\n').size)
        assertEquals("他抬头看向远处的山峦，云层很低，像是要落下雨来。她没有说话。", out)
    }

    @Test
    fun `每行一段的长文本保持原有段落结构`() {
        val lines = listOf(
            "这是第一个自然段，内容足够长，明显不是被屏幕宽度硬生生截断的半句话。",
            "这是第二个自然段，同样以句号收尾，说明源文件本来就是每行一段的格式。",
            "这是第三个自然段，阅读器不应把它和前面两段合并成一整块文字。"
        )
        val out = TextCleaner.clean(lines.joinToString("\n"))
        assertEquals(3, out.split('\n').size)
        assertEquals(lines, out.split('\n'))
    }

    @Test
    fun `原文空行是明确段落边界 不做软换行合并`() {
        val raw = "甲说了一句话，\n\n乙接着说，\n没有说完。"
        val out = TextCleaner.clean(raw)
        assertEquals("甲说了一句话，", out.split('\n')[0])
        assertEquals("乙接着说，没有说完。", out.split('\n')[1])
    }

    @Test
    fun `章节标题独立成段且不被合并`() {
        val raw = "上一章的最后一句没有标点\n第二章 山雨欲来\n新的一段开始了，\n还没结束。"
        val out = TextCleaner.clean(raw)
        val paras = out.split('\n')
        assertEquals(3, paras.size)
        assertEquals("第二章 山雨欲来", paras[1])
        assertTrue(TextCleaner.isChapterTitleLike("第12章 归途"))
        assertTrue(TextCleaner.isChapterTitleLike("Chapter 3 The Return"))
        assertFalse(TextCleaner.isChapterTitleLike("这是一段普通正文内容。"))
    }

    @Test
    fun `英文软换行在单词之间补空格`() {
        val raw = "This is a very long\nline that was wrapped\nby the converter."
        val out = TextCleaner.clean(raw)
        assertEquals("This is a very long line that was wrapped by the converter.", out.split('\n')[0])
    }

    @Test
    fun `关闭智能合并时每行仍是独立段落`() {
        val raw = "第一行没有标点\n第二行接着说\n第三行结束了。"
        val out = TextCleaner.clean(raw, smartJoin = false)
        assertEquals(3, out.split('\n').size)
    }

    @Test
    fun `零宽字符与不可见控制符不会进入正文`() {
        val raw = "abc\u200Bdef\u2060ghi。"
        assertEquals("abcdefghi。", TextCleaner.clean(raw))
    }

    @Test
    fun `纯空白文本返回空串`() {
        assertEquals("", TextCleaner.clean("  \n \r\n\u3000\n\n"))
        assertEquals("", TextCleaner.clean(""))
    }

    @Test
    fun `cleanWhitespace 保留单个空行作段落分隔`() {
        val raw = "甲。\n\n\n\n乙。   \n 丙"
        val out = TextCleaner.cleanWhitespace(raw)
        assertEquals("甲。\n\n乙。\n丙", out)
    }

    @Test
    fun `行内连续空格压缩但不破坏英文单词间距`() {
        val raw = "Hello    world  and 中文   之间。"
        assertEquals("Hello world and 中文 之间。", TextCleaner.clean(raw, smartJoin = false))
    }
}
