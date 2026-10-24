package com.zeoffice.einkreader.utils

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast

/**
 * 阅读页物理翻页键分发器 — 在 Activity 层统一拦截电纸书实体按键。
 *
 * 墨水屏电纸书（Bigme B751C 等）实体翻页键的键值五花八门：
 *   - 标准：音量键 24/25、PAGE_UP/DOWN 92/93、DPAD 19-22
 *   - 厂商自定义（常见把翻页键映射成媒体键 / 导航键）：87/88、269/270 等
 *
 * 本分发器对标准键做固定映射，并支持用户在设置里补充「自定义兼容键值」（customPrevKeys/customNextKeys），
 * 以适配任意厂商的非常规键值，无需改代码发版。
 *
 * 设计要点（"只优化不魔改"）：
 *  1. 在 [android.app.Activity.dispatchKeyEvent] 最早阶段拦截。音量键若不在 Activity 层吃掉，
 *     会一路下传到系统触发音量面板，因此必须在 super 之前 return true 消费。
 *  2. 通过 [handler] 把"是否拦截 / 翻哪页"交给当前阅读界面决定；[handler] 为空（不在阅读态）
 *     时完全不拦截，书库/设置等界面与系统音量、媒体键照常工作。
 *  3. 仅当 [handler] 返回 true 才消费事件，避免误伤其它按键。
 */
object ReaderKeyDispatcher {

    /**
     * 阅读界面注册的处理器。接收完整的 [KeyEvent]，返回 true 表示"本事件已被阅读页消费"
     * （翻页或屏蔽），Activity 据此直接 return true，不再下传系统。
     * 阅读界面内部负责：判断当前是否应启用翻页键、键值到上一页/下一页的映射、
     * 仅在 ACTION_DOWN 且首次按下 (repeatCount==0) 时翻页、菜单态消费但不翻页。
     */
    var handler: ((event: KeyEvent) -> Boolean)? = null

    /** 诊断模式：为真时 MainActivity 对每个按键弹 Toast 显示 keyCode / action，便于真机排查 */
    var debugRaw: Boolean = false

    /** 用户自定义的兼容键值（逗号分隔整数，由设置页写入）：上一页键 / 下一页键。
     *  用于适配非常规厂家键值，默认已预置常见媒体/导航键（87/88/269/270）。 */
    var customPrevKeys: Set<Int> = emptySet()
    var customNextKeys: Set<Int> = emptySet()

    /** 标准键值 → 方向（-1 上一页 / +1 下一页 / 0 不处理） */
    private fun standardDir(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_LEFT -> -1
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
        else -> 0
    }

    /** 任一来源（标准 / 自定义）的键值方向；0 表示本键不用于翻页 */
    fun keyDir(keyCode: Int): Int {
        val std = standardDir(keyCode)
        if (std != 0) return std
        if (keyCode in customPrevKeys) return -1
        if (keyCode in customNextKeys) return 1
        return 0
    }

    /** 是否值得本分发器接管的键（标准翻页键 + 用户自定义键） */
    fun isInterceptableKey(keyCode: Int): Boolean =
        standardDir(keyCode) != 0 || keyCode in customPrevKeys || keyCode in customNextKeys

    /** 是否为音量键（受「音量键翻页」开关单独控制） */
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /** 诊断：把按键原始信息以 Toast 呈现（debugRaw 关闭时 no-op） */
    fun showDebugToast(context: Context, event: KeyEvent) {
        if (!debugRaw) return
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "ACTION(${event.action})"
        }
        val dir = keyDir(event.keyCode)
        val tag = when {
            dir < 0 -> "←上一页"
            dir > 0 -> "下一页→"
            else -> "未映射"
        }
        Toast.makeText(
            context,
            "KEY=${event.keyCode} $action repeat=${event.repeatCount}  $tag",
            Toast.LENGTH_SHORT
        ).show()
    }
}
