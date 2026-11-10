package com.zeoffice.einkreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 云盘/WebDAV 账户 — 凭证仅存于本机数据库, 不上传
 *
 * provider: "webdav" | "baidu"
 *  - webdav: host + username + password
 *  - baidu : accessToken + refreshToken + expiresAt (OAuth)
 */
@Entity(tableName = "cloud_accounts")
data class CloudAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val provider: String,
    val name: String,
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val extra: String = ""
)
