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
    // ===== 正文智能整理 / 排版自适应 (v0.4.8) =====
    val cleanText: Boolean = true,     // 自动清理多余空行/空格、合并 TXT 硬换行为自然段
    val firstLineIndent: Boolean = true, // 段落首行缩进 2 个汉字宽
    val brightness: Float = 1f,       // 1f 不dim, 越小越暗
    val keepScreenOn: Boolean = true,
    // ===== 电纸书（墨水屏）适配 =====
    val einkMode: Boolean = false,      // 总开关: 关闭动画/水波纹、提高对比度、支持物理翻页键
    @Deprecated("旧三态映射已拆分为下方两个独立开关，保留字段仅用于兼容老数据迁移")
    val pageKeyAction: String = "prev_next", // 历史字段: off / prev_next / next_prev（不再被 UI 使用）
    // 物理翻页键（电纸书专属）— 仅在 einkMode 开启时生效，普通手机不受影响
    val volumeKeyPageTurn: Boolean = false,  // 音量键翻页: VOLUME_UP=上一页 / VOLUME_DOWN=下一页
    val pageKeyPageTurn: Boolean = true,    // 翻页键翻页: PAGE_UP/DPAD_UP/DPAD_LEFT=上一页; PAGE_DOWN/DPAD_DOWN/DPAD_RIGHT=下一页（默认开）
    // 自定义兼容键值（逗号分隔整数）：适配厂商非常规按键。默认预置常见媒体/导航键（87/88/269/270）
    val customPrevKeys: String = "87,269",  // 上一页自定义键
    val customNextKeys: String = "88,270",  // 下一页自定义键
    val keyDebug: Boolean = false,          // 按键诊断：开启后实体键弹 Toast 显示 keyCode
    // ===== WebDAV 自动同步 =====
    val autoSyncOnStartup: Boolean = false, // 启动进入后自动从服务器拉取并合并远程书籍与进度
    val autoSyncOnExit: Boolean = false,    // 退出程序前自动将本地书籍与进度推送至服务器
    val syncPolicy: String = "NEWER_WINS",  // 冲突解决策略: NEWER_WINS(以修改时间较新者为准)/LOCAL_WINS/REMOTE_WINS
    val syncFiles: Boolean = true,          // 自动同步是否包含书籍文件 (关闭则仅同步进度/书签)
    val syncBookmarks: Boolean = true       // 自动同步是否包含书签与笔记
)
