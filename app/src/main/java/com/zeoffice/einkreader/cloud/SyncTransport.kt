package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.data.db.SyncStateEntity
import com.zeoffice.einkreader.domain.model.CloudAccount
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 同步传输层统一接口 — WebDAV / 百度网盘 共用同一套同步编排。
 *
 * 语义约定（所有后端一致）：
 *  - 同步根目录下固定放置 `library.json`（v2 索引）与 `books/<key>.<ext>`（书籍文件）；
 *  - 传给 [uploadBook] / [downloadBook] 的 relPath 是**相对同步根目录**的路径，
 *    由各后端自行映射到 URL / 网盘绝对路径 / file_id；
 *  - [readIndex] 只在「远端尚未创建索引」时返回 null；网络错误、鉴权失败必须抛异常，
 *    否则编排层会把空索引写回远端、造成书库丢失（v0.4.8 已修过的坑，此处继续守住）。
 *  - 上传同名文件必须覆盖，不能产生副本，否则索引里的相对路径会指向旧内容。
 */
interface SyncTransport {
    /** 后端中文名，用于 UI 与错误提示 */
    val backendName: String

    /** 远端同步根目录的可读描述（UI 展示用） */
    val remoteRoot: String

    /** 准备同步目录；syncFiles=false 时可以只建根目录，不建 books/ */
    suspend fun prepare(syncFiles: Boolean)

    /** 读取 library.json；不存在返回 null，网络/解析前错误抛异常 */
    suspend fun readIndex(): String?

    /** 覆盖写入 library.json */
    suspend fun writeIndex(json: String): Boolean

    /** 上传书籍文件到 relPath（相对同步根目录），同名覆盖 */
    suspend fun uploadBook(relPath: String, file: File): Boolean

    /** 下载 relPath 指向的书籍文件到 dest；远端不存在应抛异常，不要静默返回 true */
    suspend fun downloadBook(relPath: String, dest: File): Boolean

    /**
     * 授权过程中凭据若发生轮换（如 OAuth refresh_token 轮换），
     * 返回更新后的账户供编排层持久化；无变化返回 null。
     */
    fun updatedAccount(): CloudAccount? = null
}

/**
 * 同步路径 / 基线 / 分片的纯逻辑（无 Android 与网络依赖，可 JVM 单测）。
 *
 * 基线隔离：同一台设备可能同时配置多个同步后端，每个后端各有自己的 library.json。
 * `sync_state` 表主键是 bookKey，为避免跨后端基线互相污染，新基线一律写成
 * `scope|bookKey`；同时兼容 v0.4.9 及更早版本留下的无前缀旧行（scoped 行优先）。
 */
object SyncLayout {
    const val INDEX_PATH = "library.json"
    const val BOOKS_DIR = "books"
    const val ROOT_FOLDER = "EasyReader"

    /** 网盘分片上传的单片大小：4 MiB，百度/阿里均接受该尺寸 */
    const val BLOCK_SIZE = 4 * 1024 * 1024

    /** 单个后端索引上限，防止异常响应把内存打爆 */
    const val MAX_INDEX_BYTES = 8 * 1024 * 1024L

    fun bookPath(key: String, format: String): String =
        "$BOOKS_DIR/$key.${format.lowercase()}"

    /** 拼接远端绝对路径，避免重复/缺失斜杠；rel 必须是相对路径 */
    fun join(root: String, rel: String): String {
        val r = root.trimEnd('/')
        return "$r/${rel.trimStart('/')}"
    }

    /** 后端隔离作用域：provider + 账户 id */
    fun scopeOf(account: CloudAccount): String = "${account.provider}:${account.id}"

    fun scopedKey(scope: String, key: String): String = "$scope|$key"

    /**
     * 取出某个后端可用的基线：
     *  1. 先读「无前缀」的旧行（v0.4.9 及以前写入），保证覆盖安装后不误判为全量冲突；
     *  2. 再用该 scope 的 scoped 行覆盖同名 key。
     */
    fun baselines(scope: String, states: List<SyncStateEntity>): Map<String, SyncStateEntity> {
        val prefix = "$scope|"
        val legacy = LinkedHashMap<String, SyncStateEntity>()
        val scoped = LinkedHashMap<String, SyncStateEntity>()
        for (s in states) {
            when {
                s.bookKey.startsWith(prefix) -> scoped[s.bookKey.removePrefix(prefix)] = s
                !s.bookKey.contains('|') -> legacy[s.bookKey] = s
                else -> { /* 其它后端的基线，忽略 */ }
            }
        }
        return legacy + scoped
    }

    /**
     * 把文件切成上传分片 [起始偏移, 长度)。空文件返回单个空片，
     * 由调用方决定拒绝还是走后端的空文件创建流程。
     */
    fun ranges(size: Long): List<Pair<Long, Int>> {
        if (size <= 0L) return listOf(0L to 0)
        val out = ArrayList<Pair<Long, Int>>()
        var offset = 0L
        while (offset < size) {
            val len = minOf(BLOCK_SIZE.toLong(), size - offset).toInt()
            out.add(offset to len)
            offset += len
        }
        return out
    }

    fun md5Hex(bytes: ByteArray): String = hash("MD5", bytes)

    fun sha1Hex(bytes: ByteArray): String = hash("SHA-1", bytes)

    /** 带上限的流读取，避免异常响应用 ByteArray 撑爆内存 */
    fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n == -1) break
                total += n
                if (total > limit) throw IOException("远端文件超过大小上限 ($limit bytes)，已中止读取")
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun hash(algo: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(algo).digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
