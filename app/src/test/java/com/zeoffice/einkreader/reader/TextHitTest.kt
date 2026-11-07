package com.zeoffice.einkreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按取词回归测试。
 *
 * 背景：v0.4.6 及之前「选字查询跳出的不是点选的字，有偏离」。
 * 根因一：命中边界取字符中心（右半字判给下一个字）+ 两端对齐的额外字距没算进去；
 * 根因二：候选词枚举把整段送进带「最长前缀匹配」的 lookupEntry，结果固定命中片段开头的词；
 * 根因三：取词窗口是「从连续汉字串开头到点击处」的长串，越点靠后越离谱。
 *
 * Paginator 依赖 android.graphics.Paint，只能在 instrumented test 里跑；
 * 这里覆盖与之解耦的纯逻辑 TextHit。
 */
class TextHitTest {

    private val dict = mapOf(
        "阅读" to "读书、阅览",
        "朋友" to "友人",
        "世界" to "世上、人间",
        "花" to "花朵",
        "山" to "山岳",
        "国" to "国家",
        "understand" to "理解、明白",
        "the" to "定冠词"
    )

    private fun lookup(q: String): Pair<String, String>? = dict[q]?.let { q to it }

    /** 等宽 20px 的 10 个字符：charXs.size = 11，末位是行尾 200 */
    private fun evenXs(count: Int = 10, width: Float = 20f): List<Float> =
        (0..count).map { it * width }

    @Test
    fun indexOfX_点击字符任意位置都返回该字符() {
        val xs = evenXs()
        for (k in 0..9) {
            val left = k * 20f
            assertEquals("左边缘 k=$k", k, TextHit.indexOfX(xs, left + 0.1f))
            assertEquals("中心 k=$k", k, TextHit.indexOfX(xs, left + 10f))
            assertEquals("右边缘 k=$k", k, TextHit.indexOfX(xs, left + 19.9f))
        }
    }

    @Test
    fun indexOfX_两端对齐额外字距下仍准确() {
        // 每字 20px + 对齐间隙 4px，字符实际占 [k*24, k*24+24)
        val xs = (0..10).map { it * 24f }
        for (k in 0..9) {
            assertEquals("间隙行 k=$k 中心", k, TextHit.indexOfX(xs, k * 24f + 12f))
            assertEquals("间隙行 k=$k 右缘", k, TextHit.indexOfX(xs, k * 24f + 23.9f))
        }
    }

    @Test
    fun indexOfX_页边距外侧钳到首字与末字() {
        val xs = evenXs()
        assertEquals(0, TextHit.indexOfX(xs, -30f))
        assertEquals(9, TextHit.indexOfX(xs, 500f))
    }

    @Test
    fun resolveWord_点词首_命中以点选字开头的词() {
        val r = TextHit.resolveWord("阅读器很好用", 0, ::lookup)
        assertEquals("阅读", r?.first)
        assertEquals('阅', r?.third)
    }

    @Test
    fun resolveWord_点词中_命中包含点选字的词() {
        // 点「读」，词首在其左侧，仍应查到「阅读」而不是退回到别的字
        val r = TextHit.resolveWord("阅读器很好用", 1, ::lookup)
        assertEquals("阅读", r?.first)
        assertEquals('读', r?.third)
    }

    @Test
    fun resolveWord_绝不返回不含点选字的词() {
        // 旧实现第一次就把整段 "世界阅读" 交给 lookupEntry，
        // 其最长前缀匹配会立刻返回 "世界" —— 与点击的「阅」无关。
        val queries = mutableListOf<String>()
        val record: (String) -> Pair<String, String>? = { q ->
            queries.add(q)
            lookup(q)
        }
        val r = TextHit.resolveWord("世界阅读", 2, record)
        assertEquals("阅读", r?.first)
        assertEquals('阅', r?.third)
        assertTrue("候选必须都以点选字为锚: $queries", queries.all { it.contains('阅') })
        assertTrue("不应再把整段送去查询: $queries", "世界阅读" !in queries)
    }

    @Test
    fun resolveWord_单字兜底() {
        val r = TextHit.resolveWord("山花烂漫", 1, ::lookup)
        assertEquals("花", r?.first)
        assertEquals('花', r?.third)
    }

    @Test
    fun resolveWord_未收录返回null() {
        assertNull(TextHit.resolveWord("麒麟鹦鹉", 0, ::lookup))
    }

    @Test
    fun resolveWord_英文整词不因maxLen被截断() {
        // "understand" 长 10 > 默认 maxLen 6，旧枚举会退化成单字母查询
        for (ci in 0..9) {
            val r = TextHit.resolveWord("understand", ci, ::lookup)
            assertEquals("ci=$ci", "understand", r?.first)
            assertEquals("understand"[ci], r?.third)
        }
    }

    @Test
    fun resolveWord_点击下标越界时安全钳制() {
        val r = TextHit.resolveWord("山花", 99, ::lookup)
        assertEquals('花', r?.third)
    }
}
