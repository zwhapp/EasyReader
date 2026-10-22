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
    val keepScreenOn: Boolean
)
