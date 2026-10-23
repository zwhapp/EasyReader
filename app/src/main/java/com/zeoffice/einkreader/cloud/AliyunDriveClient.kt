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
import org.json.JSONObject
import java.io.File

/**
 * 阿里云盘客户端 (开放 API)
 *
 * 说明: 用户需自备 refresh_token (从阿里云盘网页端获取)。
 * 授权: POST https://auth.aliyundrive.com/v1/account/token  (grant_type=refresh_token)
 * 文件: https://api.aliyundrive.com/adrive/v1/file/list | /file/get
 *
 * 注意: 阿里云盘接口可能调整, 此处为社区通用实现, 需用户自行验证。
 */
class AliyunDriveClient(
    private val account: CloudAccount,
    private val client: OkHttpClient = OkHttpClient()
) : CloudProvider {

    override val provider: String = "ali"
    override val displayName: String = "阿里云盘"

    private var accessToken: String = account.accessToken
    private var driveId: String = account.extra
    private val tokenUrl = "https://auth.aliyundrive.com/v1/account/token"
    private val listUrl = "https://api.aliyundrive.com/adrive/v1/file/list"
    private val getUrl = "https://api.aliyundrive.com/adrive/v1/file/get"

    override suspend fun needsAuthorize(): Boolean = account.refreshToken.isBlank()
    override suspend fun isAuthorized(): Boolean = accessToken.isNotBlank()
    override suspend fun authorize(): Boolean = refreshToken()

    private suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", account.refreshToken)
            }.toString()
            val req = Request.Builder()
                .url(tokenUrl)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            accessToken = json.optString("access_token")
            driveId = json.optString("default_drive_id")
            resp.close()
            accessToken.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    private fun authed(url: String, jsonBody: String): Request =
        Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

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
            val resp = client.newCall(authed(listUrl, body)).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
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
            val body = JSONObject().apply {
                put("drive_id", driveId)
                put("file_id", file.extra)
                put("fields", "*")
            }.toString()
            val resp = client.newCall(authed(getUrl, body)).execute()
            val json = JSONObject(resp.body?.string() ?: "{}")
            val url = json.optString("url")
            if (url.isBlank()) return@withContext false
            val dl = client.newCall(Request.Builder().url(url).build()).execute()
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
        } catch (e: Exception) {
            false
        }
    }

    /** 取出刷新后的 token / driveId 以便持久化 */
    fun updatedAccount(): CloudAccount =
        account.copy(accessToken = accessToken, extra = driveId)

    override fun childPath(parent: String, name: String): String = name
}
