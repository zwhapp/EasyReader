package com.zeoffice.einkreader.webdav

import android.content.Context
import java.util.UUID

/**
 * 同步用的稳定设备标识。HLC 在「时钟完全相同」时靠 deviceId 做确定性决胜，
 * 因此同一台设备必须在多次启动/多次同步之间保持不变。
 *
 * 存 SharedPreferences 而非数据库：DB 升级/清库不应改变设备身份，
 * 也避免为了一个字符串去动 reading_settings 的 Room schema。
 */
object SyncDeviceId {
    private const val PREF = "easyreader_sync"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY_DEVICE_ID, "").orEmpty()
        if (existing.isNotBlank()) return existing
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        sp.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
