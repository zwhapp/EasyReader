package com.zeoffice.einkreader.reader

import org.junit.Assert.*
import org.junit.Test

class TextLayoutTest {

    @Test
    fun `手机窄屏占满可用宽度且左边距等于设定值`() {
        // 1080px 宽、左右各 44px 边距 → 992px 可用，小于 1440px 上限
        val box = TextLayout.contentBox(1080, 1920, 44, 36, 1440)
        assertEquals(44, box.left)
        assertEquals(992, box.width)
        assertEquals(36, box.top)
        assertEquals(1848, box.height)
    }

    @Test
    fun `宽屏平板限制最大行宽并水平居中`() {
        // 2400px 宽、左右各 60px → 可用 2280px，超过 1440px 上限
        val box = TextLayout.contentBox(2400, 1600, 60, 40, 1440)
        assertEquals(1440, box.width)
        assertEquals(60 + (2280 - 1440) / 2, box.left)
        assertEquals(480, box.left)
    }

    @Test
    fun `边距大于屏宽时仍返回合法正数区域`() {
        val box = TextLayout.contentBox(200, 300, 300, 300, 1440)
        assertEquals(1, box.width)
        assertEquals(1, box.height)
        assertTrue(box.left >= 0)
    }

    @Test
    fun `行高不小于字体实际高度`() {
        assertEquals(38f, TextLayout.lineHeight(19f, 2f, 30f), 0.01f)
        // 行距过小的时候由字体度量兜底，避免文字重叠
        assertEquals(33.6f, TextLayout.lineHeight(19f, 1.0f, 30f), 0.01f)
    }

    @Test
    fun `首行缩进为两个汉字宽 关闭时为零`() {
        assertEquals(38f, TextLayout.firstLineIndent(19f, true), 0.01f)
        assertEquals(0f, TextLayout.firstLineIndent(19f, false), 0.01f)
    }
}
