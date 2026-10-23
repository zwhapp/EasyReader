package com.zeoffice.einkreader.parser.mobi

import com.zeoffice.einkreader.parser.azw.AzwParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOBI / AZW3 解析器单元测试 (JVM, 无 Android 依赖)
 *
 * 测试文件: 同目录 test_book.mobi / test_book.azw3
 * 二者均由 mkmobi.py 生成, 含真实 PalmDOC(LZ77) 压缩 + 多字节中文 + 重复段落(触发回引)。
 *
 * 关键回归: 解码后正文不得出现乱码替换符, 且回引/字面量/多字节均正确还原。
 */
class MobiParserTest {

    private fun res(name: String): File =
        File("src/test/java/com/zeoffice/einkreader/parser/mobi/$name")

    @Test
    fun testParseMobi() {
        val f = res("test_book.mobi")
        assertTrue("测试文件应存在: ${f.absolutePath}", f.exists())
        val book = MobiParser().parse(f.absolutePath)
        assertEquals("测试书籍标题", book.title)
        assertEquals("测试作者", book.author)
        assertTrue("应有 >=3 章, 实际 ${book.chapters.size}", book.chapters.size >= 3)
        assertTrue(book.chapters.any { it.title.contains("第一章") })
        assertTrue(book.chapters.any { it.title.contains("第三章") })
        val all = book.chapters.joinToString("\n") { it.text }
        assertTrue("正文应含样例段落", all.contains("这是一段用于验证 MOBI 解析的样例文本"))
        assertTrue("正文应含修复标记", all.contains("乱码问题已经修复"))
        assertFalse("不应残留 HTML 标签", all.contains("<p>"))
        assertFalse("回归: 解码不得出现乱码替换符", all.contains('\uFFFD'))
    }

    @Test
    fun testParseAzw3() {
        val f = res("test_book.azw3")
        assertTrue("测试文件应存在: ${f.absolutePath}", f.exists())
        // AZW3 经 AzwParser -> MobiParser 解码 (与运行时一致)
        val book = AzwParser().parse(f.absolutePath)
        assertEquals("测试书籍标题", book.title)
        assertEquals("测试作者", book.author)
        assertTrue("应有 >=3 章, 实际 ${book.chapters.size}", book.chapters.size >= 3)
        val all = book.chapters.joinToString("\n") { it.text }
        assertTrue("AZW3 正文应含样例段落", all.contains("这是一段用于验证 MOBI 解析的样例文本"))
        assertTrue("AZW3 正文应含修复标记", all.contains("乱码问题已经修复"))
        assertFalse("回归: AZW3 解码不得出现乱码替换符", all.contains('\uFFFD'))
    }
}
