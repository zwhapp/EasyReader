package com.zeoffice.einkreader.domain.model

/**
 * 云盘/WebDAV 文件项 (统一模型)
 */
data class CloudFile(
    val name: String,
    val path: String,        // 绝对 URL 或远端路径
    val isDir: Boolean,
    val sizeBytes: Long = 0,
    val lastModified: String? = null,
    val extra: String = ""   // 提供商附加信息 (如 file_id)
)
