package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudFile
import java.io.File

/**
 * 云存储统一接口 — 本地/WebDAV/百度网盘/阿里云盘 均实现此接口
 */
interface CloudProvider {
    val provider: String
    val displayName: String

    /** 是否需要授权 (OAuth); WebDAV 不需要 */
    suspend fun needsAuthorize(): Boolean = false

    /** 是否已授权 */
    suspend fun isAuthorized(): Boolean = true

    /** 触发授权 (返回是否成功) */
    suspend fun authorize(): Boolean = true

    /** 列目录 */
    suspend fun listFiles(path: String): List<CloudFile>

    /** 下载文件到本地 dest, 带进度回调 */
    suspend fun download(
        file: CloudFile,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean

    /** 拼接子目录/文件的全路径 (各提供商实现) */
    fun childPath(parent: String, name: String): String =
        if (parent.endsWith("/")) "$parent$name" else "$parent/$name"
}
