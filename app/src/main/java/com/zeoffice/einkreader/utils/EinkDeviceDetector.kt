package com.zeoffice.einkreader.utils

import android.os.Build

/**
 * 墨水屏（电纸书）设备识别 — 用于在目标机型（Bigme B751C 等）上默认开启电纸书适配。
 *
 * 墨水屏设备的系统刷新机制与手机不同：局部刷新会残留灰度残影，动画/半透明/抗锯齿
 * 都会明显拖慢刷新并降低文字清晰度。识别到已知墨水屏品牌/机型时，推荐默认开启
 * [com.zeoffice.einkreader.domain.model.ReadingSettings.einkMode]。
 *
 * 说明：仅做品牌/机型字符串匹配，不涉及任何权限或隐私读取；识别不到时也完全不影响功能，
 * 用户可随时在「阅读设置 → 电纸书适配」中手动开关。
 */
object EinkDeviceDetector {

    // 已知墨水屏品牌 (Build.BRAND, 小写)
    private val EINK_BRANDS = listOf(
        "bigme", "onyx", "boox", "boyue", "kindle", "hisense",
        "mobiscribe", "likebook", "nook", "iray", "xreader", "supernote"
    )

    // 已知墨水屏机型关键字 (Build.MODEL, 小写) — 覆盖 Bigme B751C 等
    private val EINK_MODEL_KEYWORDS = listOf(
        "b751", "b1051", "b6", "p10", "note", "tab", "leaf", "nova", "palma",
        "go color", "gocolor", "page", "libra", "sage", "oasis", "paperwhite", "inkpad"
    )

    /** 是否推荐开启电纸书模式（识别到已知墨水屏设备） */
    fun recommendEink(): Boolean {
        val brand = safe { Build.BRAND }.lowercase()
        val model = safe { Build.MODEL }.lowercase()
        val device = safe { Build.DEVICE }.lowercase()
        if (EINK_BRANDS.any { brand.contains(it) }) return true
        if (EINK_MODEL_KEYWORDS.any { k -> model.contains(k) || device.contains(k) }) return true
        return false
    }

    /** 是否为目标机型 Bigme B751C（用于日志/默认提示，不影响逻辑分支） */
    fun isBigmeB751C(): Boolean =
        safe { Build.BRAND }.lowercase().contains("bigme") &&
            safe { Build.MODEL }.lowercase().let { it.contains("b751") || it.contains("b-751") }

    /**
     * 给用户的设备识别提示文案（仅识别到墨水屏时返回非空），
     * 用于「阅读设置 → 电纸书适配」分区说明，告知已为其默认开启适配。
     */
    fun deviceLabel(): String {
        val model = safe { Build.MODEL }
        return when {
            isBigmeB751C() ->
                "已识别为 Bigme B751C（7″ 彩墨屏电纸书），已为你默认开启电纸书模式与物理翻页键"
            safe { Build.BRAND }.lowercase().contains("bigme") ->
                "已识别为 Bigme 墨水屏设备，已为你默认开启电纸书模式与物理翻页键"
            recommendEink() ->
                "已识别为墨水屏设备（$model），已为你默认开启电纸书模式"
            else -> ""
        }
    }

    private inline fun safe(block: () -> String): String = runCatching(block).getOrDefault("")
}
