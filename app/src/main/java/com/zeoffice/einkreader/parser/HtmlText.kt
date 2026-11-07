package com.zeoffice.einkreader.parser

import kotlin.math.min

/**
 * HTML → 纯文本（纯 Kotlin，无第三方依赖，供 EPUB / MOBI / AZW 共用）。
 *
 * 旧实现的问题：
 *  - 只处理 5~7 个命名实体，MOBI 中文常见的 `&#x4E2D;` / `&#20013;` 会原样显示
 *  - 只把 `</p>`、`<br>` 转换行，`<div>/<h1-6>/<li>/<blockquote>` 不换行 → 整章粘成一大段
 *  - 用连续 .replace("&amp;","&") 会二次解码：`&amp;lt;` 错误地变成 `<`
 *  - HTML 注释、脚本样式未去除
 *
 * 这里用一次正则遍历做实体解码，天然避免二次解码；块级标签统一产生换行，
 * 后续再由 TextCleaner 做空行/空格整理。
 */
object HtmlText {

    private const val MAX_INPUT = 8 * 1024 * 1024 // 单章上限，防止 zip bomb / 巨型 HTML

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]*);")
    private val NAMED = mapOf(
        "nbsp" to "\u00A0", "amp" to "&", "lt" to "<", "gt" to ">",
        "quot" to "\"", "apos" to "'", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "middot" to "·", "ensp" to " ", "emsp" to " ",
        "thinsp" to " ", "zwj" to "", "zwnj" to ""
    )

    fun toPlainText(html: String): String {
        // 超大输入截断而不是整章丢弃（后续 TextCleaner 仍会整理）
        val src = if (html.length > MAX_INPUT) html.substring(0, MAX_INPUT) else html
        var s = src
        s = Regex("(?is)<!--.*?-->").replace(s, "")
        s = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>").replace(s, "")
        // <br> 是段内换行；块级标签闭合才是段落边界，用空行标记，
        // 这样 TextCleaner 会把它当成明确的段落分隔而不会把整章粘成一段。
        s = Regex("(?i)<(br|hr|mbp:pagebreak)\\s*/?>").replace(s, "\n")
        s = Regex("(?i)</(p|div|h[1-6]|li|blockquote|tr|section|article|ul|ol|table)>").replace(s, "\n\n")
        s = Regex("(?i)<(h[1-6])[^>]*>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = decodeEntities(s)
        return Regex("\n{3,}").replace(s, "\n\n").trim()
    }

    /** 单次遍历解码：命名实体、十进制与十六进制数字实体；非法码点直接保留原文 */
    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) -> codePoint(body.substring(2), 16)
            body.startsWith("#") -> codePoint(body.substring(1), 10)
            else -> NAMED[body] ?: m.value
        }
    }

    private fun codePoint(digits: String, radix: Int): String {
        if (digits.isEmpty()) return ""
        return try {
            val cp = java.lang.Long.parseLong(digits, radix)
            if (cp in 1..0x10FFFF) String(Character.toChars(cp.toInt())) else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 从 HTML 里取标题：优先 h1~h3，其次 <title> */
    fun extractTitle(html: String): String {
        val src = if (html.length > MAX_INPUT) html.substring(0, min(MAX_INPUT, 200_000)) else html
        val h = Regex("(?is)<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>").find(src)
            ?.groupValues?.get(1)
        val t = Regex("(?is)<title[^>]*>([\\s\\S]*?)</title>").find(src)
            ?.groupValues?.get(1)
        return decodeEntities(Regex("<[^>]+>").replace(h ?: t ?: "", "").trim())
    }
}
