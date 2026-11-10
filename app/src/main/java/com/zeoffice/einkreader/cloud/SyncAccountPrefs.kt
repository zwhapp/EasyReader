package com.zeoffice.einkreader.cloud

import android.content.Context

/**
 * 同步偏好 — 供「设置页同步方式」与「启动拉取 / 退出推送」使用。
 * 存 SharedPreferences 而非 Room：避免为一个 UI 偏好改动 DB 结构（迁移铁律），
 * 且与 SyncDeviceId 的做法一致。
 */
class SyncAccountPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sync_account_prefs", Context.MODE_PRIVATE)

    /** 0 = 未显式选择，自动同步退回第一个可用账户 */
    fun defaultId(): Int = prefs.getInt(KEY_DEFAULT_ACCOUNT, 0)

    fun setDefaultId(id: Int) = prefs.edit().putInt(KEY_DEFAULT_ACCOUNT, id).apply()

    /** "" = 未显式选择同步方式，设置页按已有账户推荐 */
    fun provider(): String = prefs.getString(KEY_PROVIDER, "") ?: ""

    fun setProvider(provider: String) = prefs.edit().putString(KEY_PROVIDER, provider).apply()

    private companion object {
        const val KEY_DEFAULT_ACCOUNT = "default_account_id"
        const val KEY_PROVIDER = "default_provider"
    }
}
