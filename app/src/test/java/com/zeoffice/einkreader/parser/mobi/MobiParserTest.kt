package com.zeoffice.einkreader.parser.mobi

import com.zeoffice.einkreader.parser.azw.AzwParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOBI / AZW3 解析器单元测试 (JVM, 无 Android 依赖)
 * 测试文件: 同目录 test_book.mobi (PalmDOC 压缩) / test_book.azw3 (无压缩 + trailing entries)
 */
class MobiParserTest {

    private fun res(name: String): File =
        File("src/test/java/com/zeoffice/einkreader/parser/mobi/$name")

    @Test
    fun testParseMobi() {
        val f = res("test_book.mobi")
        assertTrue(f.exists())
        val book = MobiParser().parse(f.absolutePath)
        assertEquals("MOBI测试书", book.title)
        assertEquals("测试作者", book.author)
        assertTrue("应有 >=3 章, 实际 ${book.chapters.size}", book.chapters.size >= 3)
        assertTrue(book.chapters.any { it.title.contains("第一章") })
        assertTrue(book.chapters.any { it.title.contains("第三章") })
        assertTrue("正文应包含章节内容", book.chapters.first().text.contains("山雨欲来风满楼"))
        assertTrue("不应残留 HTML 标签", !book.chapters.first().text.contains("<p>"))
    }

    @Test
    fun testParseAzw3() {
        val f = res("test_book.azw3")
        assertTrue(f.exists())
        val book = AzwParser().parse(f.absolutePath)
        assertEquals("AZW3测试书", book.title)
        assertEquals("测试作者", book.author)
        assertTrue("应有 >=3 章, 实际 ${book.chapters.size}", book.chapters.size >= 3)
        // trailing entries 已剥离, 不应有垃圾字符
        val all = book.chapters.joinToString { it.text }
        assertTrue(all.contains("尘埃落定"))
        assertTrue("不应包含 trailing 垃圾字节", !all.contains('\uAAAA'))
    }
}
