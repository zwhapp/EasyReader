package com.zeoffice.einkreader.presentation.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 阅读主题配色 — 白天 / 护眼(米黄) / 豆沙绿 / 夜间 / 电纸书(纯黑白高对比)
 */
data class ReaderColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val primary: Color,
    val isDark: Boolean
)

val READER_THEMES: Map<String, ReaderColors> = mapOf(
    "day" to ReaderColors(Color(0xFFF7F7F2), Color(0xFF1A1A1A), Color(0xFFEDEDE6), Color(0xFF3A6EA5), false),
    "sepia" to ReaderColors(Color(0xFFF5ECD7), Color(0xFF5B4636), Color(0xFFEBDFC2), Color(0xFF8A6D3B), false),
    "eye" to ReaderColors(Color(0xFFC7EDCC), Color(0xFF2E3D32), Color(0xFFB6E0BB), Color(0xFF3C7A46), false),
    "night" to ReaderColors(Color(0xFF121212), Color(0xFFC8C8C8), Color(0xFF1F1F1F), Color(0xFF5B8DEF), true),
    // 电纸书模式专用：纯白底 + 纯黑字，最大化对比度与文字清晰度（墨水屏灰度有限）
    "eink" to ReaderColors(Color(0xFFFFFFFF), Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF000000), false)
)

fun readerColorScheme(c: ReaderColors): ColorScheme =
    if (c.isDark) darkColorScheme(
        background = c.background, onBackground = c.onBackground,
        surface = c.surface, primary = c.primary,
        onSurface = c.onBackground, surfaceVariant = c.surface,
        onSurfaceVariant = c.onBackground
    ) else lightColorScheme(
        background = c.background, onBackground = c.onBackground,
        surface = c.surface, primary = c.primary,
        onSurface = c.onBackground, surfaceVariant = c.surface,
        onSurfaceVariant = c.onBackground
    )

/**
 * 电纸书（墨水屏）全局灰阶主题 — 把所有 Material3 颜色角色降为灰阶，
 * 去除彩色与色调阴影，最大限度降低墨水屏刷新残影、提升对比与文字清晰度。
 * 仅作颜色角色替换，不改动布局/交互，符合「只优化不魔改」。
 */
fun einkColorScheme(): ColorScheme {
    val base = lightColorScheme()
    return base.copy(
        primary = Color(0xFF000000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE0E0E0),
        onPrimaryContainer = Color(0xFF000000),
        inversePrimary = Color(0xFF000000),
        secondary = Color(0xFF424242),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE0E0E0),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF424242),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0E0E0),
        onTertiaryContainer = Color(0xFF000000),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFECECEC),
        onSurfaceVariant = Color(0xFF3A3A3A),
        inverseSurface = Color(0xFF000000),
        inverseOnSurface = Color(0xFFFFFFFF),
        error = Color(0xFF000000),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFE0E0E0),
        onErrorContainer = Color(0xFF000000),
        outline = Color(0xFF9E9E9E),
        outlineVariant = Color(0xFFCFCFCF),
        scrim = Color(0xFF000000),
        surfaceTint = Color(0xFFFFFFFF)
    )
}

// 书库界面 (简洁白底)
val LibraryBackground = Color(0xFFF4F4F2)
val LibraryText = Color(0xFF222222)
val LibrarySecondary = Color(0xFF767676)
val LibraryAccent = Color(0xFF3A6EA5)
