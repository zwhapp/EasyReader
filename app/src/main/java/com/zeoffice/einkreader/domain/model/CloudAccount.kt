package com.zeoffice.einkreader.domain.model

/**
 * 云盘 / WebDAV 账户 (领域模型)
 */
data class CloudAccount(
    val id: Int = 0,
    val provider: String,   // "webdav" | "baidu" | "ali"
    val name: String,
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val extra: String = ""
)
