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
    val keepScreenOn: Boolean = true,
    // ===== 电纸书（墨水屏）适配 =====
    val einkMode: Boolean = false,      // 总开关: 关闭动画/水波纹、提高对比度、支持物理翻页键
    @Deprecated("旧三态映射已拆分为下方两个独立开关，保留字段仅用于兼容老数据迁移")
    val pageKeyAction: String = "prev_next", // 历史字段: off / prev_next / next_prev（不再被 UI 使用）
    // 物理翻页键（电纸书专属）— 仅在 einkMode 开启时生效，普通手机不受影响
    val volumeKeyPageTurn: Boolean = false,  // 音量键翻页: VOLUME_UP=上一页 / VOLUME_DOWN=下一页
    val pageKeyPageTurn: Boolean = true     // 翻页键翻页: PAGE_UP/DPAD_UP/DPAD_LEFT=上一页; PAGE_DOWN/DPAD_DOWN/DPAD_RIGHT=下一页（默认开）
)
