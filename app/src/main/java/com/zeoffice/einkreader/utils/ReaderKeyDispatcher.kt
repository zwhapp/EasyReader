package com.zeoffice.einkreader.utils

import android.view.KeyEvent

/**
 * 阅读页物理翻页键分发器 — 在 Activity 层统一拦截电纸书实体按键。
 *
 * 墨水屏电纸书（Bigme B751C 等）普遍配备实体翻页键，系统派发的键值五花八门：
 *   - 音量键   KEYCODE_VOLUME_UP / VOLUME_DOWN（多数电纸书直接复用音量键做翻页）
 *   - 翻页键   KEYCODE_PAGE_UP / KEYCODE_PAGE_DOWN
 *   - 方向键   KEYCODE_DPAD_UP / DOWN / LEFT / RIGHT
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

    /** 可被本分发器接管的物理翻页键（音量 / PAGE / DPAD 四向） */
    fun isInterceptableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }
}
