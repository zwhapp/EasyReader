package com.zeoffice.einkreader.reader

/**
 * 取词命中相关的纯逻辑（无 Android 依赖，便于 JVM 单元测试）。
 *
 * 拆出来的原因：长按取词曾经出现"跳出的不是点选的字"，根因分散在坐标反查与
 * 候选词枚举两处，且都不方便在真机上逐点验证。集中到此对象后可单测覆盖。
 */
object TextHit {

    /**
     * 由预计算的字符绘制起点反查 x 落在第几个字符上。
     *
     * charXs: size = text.length + 1，charXs[k] 为第 k 个字符的起点，
     * charXs[k+1] 为第 k 个字符的右边界（含两端对齐追加的字距间隙）。
     *
     * 边界取「字符右边缘」而非「字符中心」：旧实现用 `x <= cum + cw/2` 判断，
     * 会把一个字的右半边判给下一个字，造成系统性的半字右偏；再叠加两端对齐
     * 未计入的额外字距，越靠行尾偏得越多（最多约一个字宽）。
     */
    fun indexOfX(charXs: List<Float>, x: Float): Int {
        if (charXs.size < 2) return 0
        val last = charXs.size - 2
        if (x <= charXs[0]) return 0
        for (k in 0 until last) {
            if (x < charXs[k + 1]) return k
        }
        return last
    }

    /**
     * 以「点选字」为锚解析词典候选词。
     *
     * 返回的词一定包含点选字；优先级：
     *   1) 以点选字开头的多字词（长 -> 短）—— 点「阅」查「阅读」
     *   2) 包含点选字、词首在其左侧的多字词（长 -> 短）—— 点「读」查「阅读」
     *   3) 点选单字本身
     *
     * [lookup] 必须是**精确查询**：旧实现传入的是 DictionaryRepository.lookupEntry，
     * 它内部带「中文最长前缀匹配」，把整行片段送进去会直接返回片段开头的词，
     * 与点击位置完全无关 —— 这是"点哪个字都弹出同一个词"的元凶。
     *
     * @return Triple(命中的规范词, 释义, 点选字)，未命中返回 null
     */
    fun resolveWord(
        word: String,
        clickIdx: Int,
        lookup: (String) -> Pair<String, String>?,
        maxLen: Int = 6
    ): Triple<String, String, Char>? {
        if (word.isEmpty() || maxLen < 1) return null
        val ci = clickIdx.coerceIn(0, word.length - 1)
        val picked = word[ci]

        // 西文：窗口本身已是完整单词（可能长于 maxLen），先整体精确查。
        // 否则点 "understand" 中间某个字母时会退化成单字母查询。
        if (word.all { it.code < 128 }) {
            val whole = lookup(word)
            if (whole != null) return Triple(whole.first, whole.second, picked)
        }

        // 1) 以点选字开头，长度 >= 2 的词
        val maxFrom = minOf(maxLen, word.length - ci)
        for (len in maxFrom downTo 2) {
            val entry = lookup(word.substring(ci, ci + len)) ?: continue
            return Triple(entry.first, entry.second, picked)
        }

        // 2) 包含点选字、词首在左侧的词；同长度下起点尽量贴近点选字
        val maxContain = minOf(maxLen, word.length)
        for (len in maxContain downTo 2) {
            val from = (ci - 1).coerceAtLeast(0)
            val to = (ci - len + 1).coerceAtLeast(0)
            for (start in from downTo to) {
                if (start + len > word.length) continue
                val entry = lookup(word.substring(start, start + len)) ?: continue
                return Triple(entry.first, entry.second, picked)
            }
        }

        // 3) 兜底：点选单字
        val single = lookup(picked.toString()) ?: return null
        return Triple(single.first, single.second, picked)
    }
}
