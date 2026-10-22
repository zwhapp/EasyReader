package com.zeoffice.einkreader.domain.model

/**
 * 全局阅读设置 (领域模型) — 排版 / 主题 / 亮度偏好
 */
data class ReadingSettings(
    val fontSizeSp: Float = 19f,
    val lineSpacing: Float = 1.6f,
    val paragraphSpacing: Int = 10,
    val marginHorizontalDp: Int = 22,
    val marginVerticalDp: Int = 18,
    val theme: String = "day",        // day / sepia / night / eye
    val fontFamily: String = "sans",  // sans / serif / song
    val pageTurn: String = "horizontal", // horizontal / vertical / scroll
    val textAlign: String = "justify",// left / justify
    val brightness: Float = 1f,       // 1f 不dim, 越小越暗
    val keepScreenOn: Boolean = true
)
