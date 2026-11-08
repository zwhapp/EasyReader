package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 阿里云盘客户端（开放 API）— list / download（导入）与目录 / 索引 / 文件上传（同步）。
 *
 * 授权: POST https://auth.aliyundrive.com/v1/account/token (grant_type=refresh_token)
 * 文件: https://api.aliyundrive.com/adrive/v1/file/{list,get,get_by_path,create,complete,delete}
 *
 * 同步布局：网盘根目录下 `EasyReader/library.json` 与 `EasyReader/books/<key>.<ext>`。
 * 阿里云盘没有「覆盖写」接口，同名文件先删除再创建，避免索引指向旧副本。
 * 注意：refresh_token 每次刷新都会轮换，[updatedAccount] 会把新值交给编排层落库。
 */
class AliyunDriveClient(
    private val account: CloudAccount,
    private val client: OkHttpClient = OkHttpClient()
) : CloudProvider, SyncTransport {

    override val provider: String = "ali"
    override val displayName: String = "阿里云盘"
    override val backendName: String = "阿里云盘"
    override val remoteRoot: String = "/${SyncLayout.ROOT_FOLDER}"

    private val apiBase = "https://api.aliyundrive.com"
    private val tokenUrl = "https://auth.aliyundrive.com/v1/account/token"

    private var accessToken: String = account.accessToken
    private var driveId: String = account.extra
    private var currentRefreshToken: String = account.refreshToken
    private var accountChanged = false

    private var rootDirId: String = ""
    private var booksDirId: String = ""

    override suspend fun needsAuthorize(): Boolean = currentRefreshToken.isBlank()
    override suspend fun isAuthorized(): Boolean = accessToken.isNotBlank()
    override suspend fun authorize(): Boolean = refreshToken()

    private suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", currentRefreshToken)
            }.toString()
            val req = Request.Builder()
                .url(tokenUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            resp.close()
            val token = json.optString("access_token")
            if (token.isBlank()) return@withContext false
            accessToken = token
            driveId = json.optString("default_drive_id").ifBlank { driveId }
            // 阿里云盘会轮换 refresh_token：不保存新值，下次同步就会鉴权失效
            json.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
                if (it != currentRefreshToken) {
                    currentRefreshToken = it
                    accountChanged = true
                }
            }
            accountChanged = true
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 取出刷新后的 token / driveId / refresh_token 以便持久化 */
    override fun updatedAccount(): CloudAccount? =
        if (accountChanged) account.copy(
            accessToken = accessToken,
            refreshToken = currentRefreshToken,
            extra = driveId
        ) else null

    private suspend fun ensureToken() {
        // 优先刷新（access_token 有效期短）；刷新失败但已有旧 token 时仍可继续尝试
        val refreshed = refreshToken()
        if (!refreshed && accessToken.isBlank()) {
            throw IOException("阿里云盘授权失败：refresh_token 无效或已过期，请重新添加账户")
        }
        if (driveId.isBlank()) throw IOException("阿里云盘未获取到 drive_id，请重新授权")
    }

    /** JSON POST，返回 (HTTP code, 响应体)；不在此处抛，由调用方按业务码判断 */
    private suspend fun postJson(url: String, body: String): Pair<Int, JSONObject> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                resp.code to json
            }
        }

    override suspend fun listFiles(path: String): List<CloudFile> = withContext(Dispatchers.IO) {
        if (accessToken.isBlank() && !refreshToken()) return@withContext emptyList()
        val parentId = if (path.isBlank() || path == "root") "root" else path
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("parent_file_id", parentId)
            put("limit", 100)
            put("order_by", "name")
            put("order_direction", "ASC")
            put("fields", "*")
        }.toString()
        try {
            val json = postJson("$apiBase/adrive/v1/file/list", body).second
            val arr = json.optJSONArray("items") ?: return@withContext emptyList()
            val out = mutableListOf<CloudFile>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val isDir = o.optString("type") == "folder"
                val size = o.optLong("size", 0L)
                val fileId = o.optString("file_id")
                out.add(CloudFile(name, fileId, isDir, size, o.optString("created_at"), fileId))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun download(
        file: CloudFile,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank() && !refreshToken()) return@withContext false
        try {
            val url = downloadUrl(file.extra.ifBlank { file.path })
                ?: return@withContext false
            val dl = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.aliyundrive.com/")
                    .build()
            ).execute()
            val total = dl.body?.contentLength() ?: -1L
            dl.body?.byteStream()?.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var r: Int
                    var d = 0L
                    while (input.read(buf).also { r = it } != -1) {
                        out.write(buf, 0, r)
                        d += r
                        onProgress(d, total)
                        // 阻塞读取循环中检查协程是否已取消, 使取消能真正中断下载
                        if (!coroutineContext.isActive) throw CancellationException("导入已取消")
                    }
                }
            }
            dl.close()
            true
        } catch (e: CancellationException) {
            // 取消必须原样透传，否则会被 catch(Exception) 吞掉变成「下载失败」
            runCatching { dest.delete() }
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // ==================== SyncTransport：目录 / 索引 / 文件 ====================

    override suspend fun prepare(syncFiles: Boolean) {
        ensureToken()
        ensureDirs(syncFiles)
    }

    private suspend fun ensureDirs(syncFiles: Boolean) {
        // 一次同步内目录 id 复用，避免每本书都重复发 get_by_path / create
        if (rootDirId.isNotBlank() && (!syncFiles || booksDirId.isNotBlank())) return
        rootDirId = ensureFolder(SyncLayout.ROOT_FOLDER, "root", remoteRoot)
        if (syncFiles) {
            booksDirId = ensureFolder(
                SyncLayout.BOOKS_DIR, rootDirId,
                SyncLayout.join(remoteRoot, SyncLayout.BOOKS_DIR)
            )
        }
    }

    private suspend fun ensureFolder(name: String, parentId: String, fullPath: String): String {
        getByPath(fullPath)?.let { return it }
        listChildren(parentId).firstOrNull { it.name == name && it.isDir }?.let { return it.id }
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("parent_file_id", parentId)
            put("name", name)
            put("type", "folder")
            put("check_name_mode", "refuse")
        }.toString()
        val (code, json) = postJson("$apiBase/adrive/v1/file/create", body)
        val id = json.optString("file_id")
        if (code in 200..299 && id.isNotBlank()) return id
        // 可能已存在（并发或 get_by_path 接口差异），复查后再决定失败
        return getByPath(fullPath)
            ?: listChildren(parentId).firstOrNull { it.name == name && it.isDir }?.id
            ?: throw IOException(
                "阿里云盘创建目录失败 $fullPath (HTTP $code ${json.optString("code", json.optString("message", ""))})"
            )
    }

    /** get_by_path 在 v1 / v2 与路径写法上各版本有差异，逐一兜底 */
    private suspend fun getByPath(path: String): String? {
        val variants = listOf(path, path.trimStart('/'))
        val endpoints = listOf("$apiBase/adrive/v1/file/get_by_path", "$apiBase/v2/file/get_by_path")
        for (url in endpoints) {
            for (p in variants) {
                val body = JSONObject().apply {
                    put("drive_id", driveId)
                    put("file_path", p)
                }.toString()
                val (code, json) = postJson(url, body)
                val id = json.optString("file_id")
                if (code in 200..299 && id.isNotBlank()) return id
                val err = json.optString("code", "")
                if (code == 404 || err.contains("NotFound", true)) continue
                // 鉴权/限流等错误不静默吞掉，避免把「读不到」误判成「索引不存在」
                if (code == 401 || code == 403) {
                    throw IOException("阿里云盘鉴权失败 (HTTP $code)：请重新添加账户刷新 refresh_token")
                }
            }
        }
        return null
    }

    private data class AliEntry(val name: String, val id: String, val isDir: Boolean)

    /** 列目录兜底查找（含分页，避免书籍超过 100 本时同名旧文件漏删） */
    private suspend fun listChildren(parentId: String): List<AliEntry> {
        val out = mutableListOf<AliEntry>()
        var marker = ""
        repeat(20) {
            val body = JSONObject().apply {
                put("drive_id", driveId)
                put("parent_file_id", parentId)
                put("limit", 100)
                put("fields", "*")
                if (marker.isNotBlank()) put("marker", marker)
            }.toString()
            val (code, json) = postJson("$apiBase/adrive/v1/file/list", body)
            if (code !in 200..299) return out
            val arr = json.optJSONArray("items") ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    AliEntry(
                        o.optString("name"),
                        o.optString("file_id"),
                        o.optString("type") == "folder"
                    )
                )
            }
            marker = json.optString("next_marker")
            if (marker.isBlank()) return out
        }
        return out
    }

    private suspend fun downloadUrl(fileId: String): String? {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("fields", "*")
        }.toString()
        val (code, json) = postJson("$apiBase/adrive/v1/file/get", body)
        if (code !in 200..299) return null
        return json.optString("url").ifBlank { json.optString("download_url") }.ifBlank { null }
    }

    override suspend fun readIndex(): String? = withContext(Dispatchers.IO) {
        ensureToken()
        ensureDirs(true)
        val path = SyncLayout.join(remoteRoot, SyncLayout.INDEX_PATH)
        val fileId = getByPath(path) ?: return@withContext null   // 首次同步：索引尚未创建
        val url = downloadUrl(fileId)
            ?: throw IOException("阿里云盘无法获取同步索引下载地址：$path")
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://www.aliyundrive.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body ?: throw IOException("读取同步索引响应为空")
            if (!resp.isSuccessful) throw IOException("读取同步索引失败 (HTTP ${resp.code})")
            String(SyncLayout.readLimited(body.byteStream(), SyncLayout.MAX_INDEX_BYTES), Charsets.UTF_8)
        }
    }

    override suspend fun writeIndex(json: String): Boolean {
        ensureToken()
        ensureDirs(true)
        val bytes = json.toByteArray(Charsets.UTF_8)
        return uploadBytes(rootDirId, SyncLayout.INDEX_PATH, bytes.size.toLong()) { _, _ -> bytes }
    }

    override suspend fun uploadBook(relPath: String, file: File): Boolean {
        if (!file.exists()) return false
        ensureToken()
        ensureDirs(true)
        return uploadBytes(booksDirId, relPath.substringAfterLast('/'), file.length()) { offset, len ->
            readBlock(file, offset, len)
        }
    }

    override suspend fun downloadBook(relPath: String, dest: File): Boolean {
        ensureToken()
        val path = SyncLayout.join(remoteRoot, relPath)
        val fileId = getByPath(path) ?: throw IOException("云端书籍不存在：$path")
        val name = relPath.substringAfterLast('/')
        return download(CloudFile(name, fileId, false, 0L, null, fileId), dest)
    }

    /** create → PUT 分片 → complete；同名文件先删除，保证索引里的路径指向最新内容 */
    private suspend fun uploadBytes(
        parentId: String,
        name: String,
        size: Long,
        read: (offset: Long, len: Int) -> ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        if (size <= 0L) throw IOException("空文件不支持上传：$name")
        // 1. 删除同名旧文件（阿里云盘无覆盖写接口）
        listChildren(parentId).firstOrNull { it.name == name && !it.isDir }?.let { old ->
            val body = JSONObject().apply {
                put("drive_id", driveId)
                put("file_id", old.id)
            }.toString()
            val (code, json) = postJson("$apiBase/adrive/v1/file/delete", body)
            if (code !in 200..299) {
                throw IOException(
                    "删除云端旧文件失败 $name (HTTP $code ${json.optString("code", "")})"
                )
            }
        }

        // 2. 申请上传
        val parts = SyncLayout.ranges(size)
        val partInfo = JSONArray()
        parts.forEachIndexed { index, _ ->
            partInfo.put(JSONObject().put("part_number", index + 1))
        }
        val createBody = JSONObject().apply {
            put("drive_id", driveId)
            put("parent_file_id", parentId)
            put("name", name)
            put("type", "file")
            put("size", size)
            put("check_name_mode", "refuse")
            put("part_info_list", partInfo)
        }.toString()
        val (code, created) = postJson("$apiBase/adrive/v1/file/create", createBody)
        if (code !in 200..299 || created.optString("file_id").isBlank()) {
            throw IOException(
                "阿里云盘创建文件失败 $name (HTTP $code ${created.optString("code", created.optString("message", ""))})"
            )
        }
        if (created.optBoolean("exist", false) || created.optBoolean("rapid_upload", false)) {
            return@withContext true   // 秒传命中，无需上传分片
        }
        val fileId = created.optString("file_id")
        val uploadId = created.optString("upload_id")
        val uploadParts = created.optJSONArray("part_info_list")
        if (uploadId.isBlank() || uploadParts == null || uploadParts.length() == 0) {
            throw IOException("阿里云盘未返回分片上传地址：$name")
        }

        // 3. 逐片 PUT（upload_url 为预签名地址，不带 Authorization）
        parts.forEachIndexed { index, (offset, len) ->
            if (!coroutineContext.isActive) throw CancellationException("同步已取消")
            var partUrl = ""
            for (i in 0 until uploadParts.length()) {
                val p = uploadParts.optJSONObject(i) ?: continue
                if (p.optInt("part_number", -1) == index + 1) {
                    partUrl = p.optString("upload_url")
                    break
                }
            }
            if (partUrl.isBlank()) partUrl = uploadParts.optJSONObject(index)?.optString("upload_url").orEmpty()
            if (partUrl.isBlank()) throw IOException("阿里云盘分片 $index 缺少上传地址")
            val bytes = read(offset, len)
            val req = Request.Builder().url(partUrl)
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("阿里云盘分片 $index 上传失败 (HTTP ${resp.code})")
                }
            }
        }

        // 4. 完成上传
        val completeBody = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("upload_id", uploadId)
        }.toString()
        val (doneCode, done) = postJson("$apiBase/adrive/v1/file/complete", completeBody)
        if (doneCode !in 200..299) {
            throw IOException(
                "阿里云盘完成上传失败 $name (HTTP $doneCode ${done.optString("code", "")})"
            )
        }
        true
    }

    private fun readBlock(file: File, offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }
}
