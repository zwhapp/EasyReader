package com.zeoffice.einkreader.reader

/**
 * 排版自适应计算（纯 Kotlin，无 Android 依赖）。
 *
 * 阅读区不再简单等于「View 宽高减去边距」：
 *  - 窄屏（手机竖屏）：占满可用宽度，一行字数随屏幕宽度自然变化
 *  - 宽屏（平板 / 横屏 / 分屏大窗）：限制最大行宽，避免一行 60+ 字难以回视；
 *    剩余宽度左右均分，正文居中，视觉更接近纸质书
 *  - 行高取「字号 × 行距」与「字体实际度量高」的较大值，避免行距调小时文字重叠
 *
 * ReaderView 只负责把这里算出的 contentLeft / contentTop 传给 Canvas 与命中检测，
 * 保证「看到的」和「点到的」始终用同一套坐标系。
 */
object TextLayout {

    /** 大屏上限：约 720dp 的阅读行宽（约 34~40 个汉字），由调用方按 density 换算成 px */
    const val MAX_LINE_WIDTH_DP = 720

    data class ContentBox(
        /** 正文绘制起点（相对 View 左上角），同时是命中检测的 x 原点 */
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    fun contentBox(
        viewWidth: Int,
        viewHeight: Int,
        marginXPx: Int,
        marginYPx: Int,
        maxLineWidthPx: Int
    ): ContentBox {
        val availW = (viewWidth - 2 * marginXPx).coerceAtLeast(1)
        val lineW = minOf(availW, maxLineWidthPx.coerceAtLeast(1))
        val left = marginXPx + (availW - lineW) / 2
        val availH = (viewHeight - 2 * marginYPx).coerceAtLeast(1)
        return ContentBox(left, marginYPx, lineW, availH)
    }

    /** 行高不小于字体自身高度，防止 lineSpacing 设到最小时上下行笔画相叠 */
    fun lineHeight(fontSizePx: Float, spacing: Float, fontMetricsHeight: Float): Float =
        maxOf(fontSizePx * spacing.coerceAtLeast(0.8f), fontMetricsHeight * 1.12f)

    /** 段落首行缩进：2 个汉字宽（中文阅读习惯）；关闭时返回 0 */
    fun firstLineIndent(hanCharWidthPx: Float, enabled: Boolean): Float =
        if (enabled) hanCharWidthPx * 2f else 0f
}
