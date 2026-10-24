package com.zeoffice.einkreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 全局阅读设置 (单例, id=1) — 排版 / 主题 / 亮度偏好
 */
@Entity(tableName = "reading_settings")
data class ReadingSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val fontSizeSp: Float,
    val lineSpacing: Float,
    val paragraphSpacing: Int,
    val marginHorizontalDp: Int,
    val marginVerticalDp: Int,
    val theme: String,          // day / sepia / night / eye
    val fontFamily: String,     // sans / serif / song
    val pageTurn: String,       // horizontal / vertical / scroll
    val textAlign: String,      // left / justify
    val brightness: Float,      // 1f = 不dim, <1 越暗
    val keepScreenOn: Boolean,
    val einkMode: Boolean,      // 电纸书模式 (总开关)
    val pageKeyAction: String,  // 历史字段: off / prev_next / next_prev（已弃用，仅保留用于老数据迁移读取）
    val volumeKeyPageTurn: Boolean, // 音量键翻页: VOLUME_UP=上一页 / VOLUME_DOWN=下一页（仅 eink 模式生效）
    val pageKeyPageTurn: Boolean   // 翻页键翻页: PAGE_UP/DPAD_UP/DPAD_LEFT=上一页; PAGE_DOWN/DPAD_DOWN/DPAD_RIGHT=下一页（默认开）
)
