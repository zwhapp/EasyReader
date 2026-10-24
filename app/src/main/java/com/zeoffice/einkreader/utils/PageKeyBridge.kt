package com.zeoffice.einkreader.utils

import android.view.KeyEvent

/**
 * 物理翻页键桥接 — 解耦 Activity 与阅读界面。
 *
 * 墨水屏电纸书（如 Bigme B751C）普遍配备实体上下翻页键，系统多以
 * [KeyEvent.KEYCODE_PAGE_UP] / [KeyEvent.KEYCODE_PAGE_DOWN] 派发。为避免在
 * Activity 中耦合阅读逻辑，这里采用「阅读界面注册处理器、Activity 转发按键」的方式：
 *
 *  - [ReaderScreen] 进入阅读态时通过 [handler] 注册回调，退出时置空；
 *  - [com.zeoffice.einkreader.MainActivity] 在 [android.app.Activity.onKeyDown] /
 *    [android.app.Activity.onKeyUp] 中识别翻页键后调用 [handler]；
 *  - 仅当 [handler] 返回 true 时消费该按键，其余界面（书库/设置等）完全不受影响。
 */
object PageKeyBridge {

    /**
     * 翻页键处理器。参数：
     *  - keyCode：原始按键码
     *  - isDown：true=按键按下 / false=按键抬起
     *  - repeat：是否系统自动重复（长按连发）
     * 返回 true 表示已消费该按键（阅读界面据此翻页或屏蔽）。
     */
    var handler: ((keyCode: Int, isDown: Boolean, repeat: Boolean) -> Boolean)? = null

    /** 识别电纸书常见物理翻页键（上/下页键） */
    fun isPageKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN -> true
        else -> false
    }
}
