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

    private inline fun safe(block: () -> String): String = runCatching(block).getOrDefault("")
}
