package com.zeoffice.einkreader.parser

import org.junit.Assert.*
import org.junit.Test

class HtmlTextTest {

    @Test
    fun `数字实体十进制与十六进制被正确解码`() {
        assertEquals("中文", HtmlText.toPlainText("&#20013;&#x6587;"))
        assertEquals("A&amp;B", HtmlText.toPlainText("A&amp;amp;B"))
    }

    @Test
    fun `实体只解码一次 不发生二次解码`() {
        // &amp;lt; 应变成字面 "&lt;"，绝不能变成 "<"
        assertEquals("&lt;", HtmlText.toPlainText("&amp;lt;"))
    }

    @Test
    fun `块级标签产生换行而不是粘成一整段`() {
        val html = "<div>第一段</div><h2>小标题</h2><p>第二段</p><li>列表项</li>"
        val out = HtmlText.toPlainText(html)
        assertTrue(out.contains("第一段"))
        assertTrue(out.contains("第二段"))
        assertTrue(out.lines().size >= 4)
    }

    @Test
    fun `script style 与 HTML 注释不进入正文`() {
        val html = "<!-- 注释文字 --><style>.x{color:red}</style>" +
                "<script>var s='不该出现';</script><p>正文内容</p>"
        val out = HtmlText.toPlainText(html)
        assertFalse(out.contains("不该出现"))
        assertFalse(out.contains("注释文字"))
        assertFalse(out.contains("color"))
        assertTrue(out.contains("正文内容"))
    }

    @Test
    fun `br 与分页标签产生换行`() {
        val out = HtmlText.toPlainText("甲<br/>乙<mbp:pagebreak/>丙")
        assertEquals(3, out.lines().size)
    }

    @Test
    fun `extractTitle 优先取 h1 其次 title`() {
        assertEquals("第一章 开端", HtmlText.extractTitle("<title>书名</title><h1>第一章 开端</h1><p>正文</p>"))
        assertEquals("书名 & 作者", HtmlText.extractTitle("<head><title>书名 &amp; 作者</title></head><p>正文</p>"))
    }

    @Test
    fun `连续空行被压缩`() {
        val out = HtmlText.toPlainText("甲\n\n\n\n\n乙")
        assertFalse(out.contains("\n\n\n"))
    }
}
