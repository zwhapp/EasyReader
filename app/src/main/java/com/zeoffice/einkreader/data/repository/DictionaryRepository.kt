package com.zeoffice.einkreader.data.repository

import android.content.Context
import java.io.IOException

/**
 * 离线内置词典 — 从 assets/dict.txt 载入内存 Map, 支持中英文取词查询。
 * 纯离线, 无需联网。可扩充 dict.txt 后重新构建。
 */
class DictionaryRepository(context: Context) {

    private val map = LinkedHashMap<String, String>()

    init {
        try {
            context.applicationContext.assets.open("dict.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val idx = t.indexOf('\t')
                    if (idx <= 0) continue
                    val word = t.substring(0, idx).trim()
                    val def = t.substring(idx + 1).trim()
                    if (word.isNotEmpty() && def.isNotEmpty()) map[word] = def
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /** 取词查询; 返回释义或 null */
    fun lookup(raw: String): String? = lookupEntry(raw)?.second

    /** 取词查询; 返回 (命中的规范词, 释义) 或 null */
    fun lookupEntry(raw: String): Pair<String, String>? {
        if (raw.isBlank()) return null
        val punct = Regex("^[\\s\\p{Punct}“”‘’（）《》【】、，。！？：；…]+|[\\s\\p{Punct}“”‘’（）《》【】、，。！？：；…]+\$")
        val word = raw.trim().replace(punct, "")
        if (word.isEmpty()) return null
        map[word]?.let { return word to it }
        map[word.lowercase()]?.let { return word to it }
        // 中文: 最长前缀匹配 (词语/成语)
        if (word.any { it.code in 0x4E00..0x9FFF }) {
            for (len in word.length downTo 1) {
                map[word.substring(0, len)]?.let { return word.substring(0, len) to it }
            }
        }
        return null
    }

    val size: Int get() = map.size
}
