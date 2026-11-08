package com.zeoffice.einkreader.cloud

import android.content.Context

/**
 * 默认同步账户 — 供「启动拉取 / 退出推送」使用。
 * 存 SharedPreferences 而非 Room：避免为一个 UI 偏好改动 DB 结构（迁移铁律），
 * 且与 SyncDeviceId 的做法一致。
 */
class SyncAccountPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sync_account_prefs", Context.MODE_PRIVATE)

    /** 0 = 未显式选择，自动同步退回第一个可用账户 */
    fun defaultId(): Int = prefs.getInt(KEY_DEFAULT_ACCOUNT, 0)

    fun setDefaultId(id: Int) = prefs.edit().putInt(KEY_DEFAULT_ACCOUNT, id).apply()

    private companion object {
        const val KEY_DEFAULT_ACCOUNT = "default_account_id"
    }
}
