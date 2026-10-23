package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * 百度网盘客户端 — 经扫码授权拿到 access_token 后负责 list / download。
 *
 * API: https://pan.baidu.com/rest/2.0/xpan/file
 * 注意: 百度要求请求携带 User-Agent, 否则部分接口拒绝 (errno)。
 */
class BaiduPanClient(
    private val account: CloudAccount,
    private val client: OkHttpClient = OkHttpClient()
) : CloudProvider {

    override val provider: String = "baidu"
    override val displayName: String = "百度网盘"

    private val apiBase = "https://pan.baidu.com/rest/2.0"
    private val token: String get() = account.accessToken

    override suspend fun needsAuthorize(): Boolean = account.accessToken.isBlank()
    override suspend fun isAuthorized(): Boolean = account.accessToken.isNotBlank()
    override suspend fun authorize(): Boolean = false

    /** errno → 可读错误描述 */
    private fun errnoMessage(errno: Int): String = when (errno) {
        -6 -> "身份验证失败：登录已过期，请重新扫码授权"
        111 -> "access_token 已过期，请重新扫码授权"
        2 -> "参数错误：目录不存在或路径非法"
        12 -> "请求过于频繁，请稍后再试"
        -7 -> "文件或目录名错误或存在非法字符"
        -8 -> "文件已存在"
        -9 -> "文件或目录不存在"
        10 -> "云端容量不足"
        // 目录权限边界: 下载类接口仅允许访问 /apps/<应用名>/ 目录
        20020 -> "百度网盘目录权限限制：第三方应用只能下载「我的网盘/我的应用数据」目录下的文件。" +
                "请在百度网盘中把这本书移动到「我的应用数据」对应的应用文件夹后，再回到本页导入"
        20011 -> "应用还在审核中：仅限前 10 个完成授权的用户测试使用"
        20012 -> "调用次数已达上限（限流）：请稍后再试，或完成应用上线审核"
        20013 -> "当前应用未开通该接口权限：请在开放平台上线审核时申请对应接口权限"
        31024 -> "没有访问权限：请检查应用的授权方式与接口权限"
        31045 -> "access_token 验证未通过：请重新扫码授权"
        31326 -> "命中防盗链：请求头异常，请稍后重试"
        else -> "百度网盘返回错误 (errno=$errno)"
    }

    /** 解析响应: 失败(含 errno)时抛出带原因的异常 */
    private fun checkResp(resp: okhttp3.Response): String {
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("百度网盘服务异常 (HTTP ${resp.code})")
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            ?: throw IOException("百度网盘响应解析失败")
        val errno = json.optInt("errno", 0)
        if (errno != 0) throw IOException(errnoMessage(errno))
        return text
    }

    override suspend fun listFiles(path: String): List<CloudFile> = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw IOException("尚未授权，请先扫码登录")
        val dir = if (path.isBlank()) "/" else path
        val url = "$apiBase/xpan/file?method=list&access_token=$token" +
                "&dir=${dir.urlEncode()}&limit=1000&order=name&desc=0" +
                "&web=1"
        val req = Request.Builder().url(url)
            .header("User-Agent", "pan.baidu.com")
            .build()
        try {
            val text = checkResp(client.newCall(req).execute())
            val json = org.json.JSONObject(text)
            val list = json.optJSONArray("list") ?: return@withContext emptyList()
            val out = mutableListOf<CloudFile>()
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val name = o.optString("server_filename").ifBlank { o.optString("filename") }
                val isDir = o.optInt("isdir", 0) == 1
                val size = o.optLong("size", 0L)
                val p = o.optString("path")
                val fsId = o.optLong("fs_id", 0L)
                out.add(CloudFile(name, p, isDir, size, null, fsId.toString()))
            }
            out
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("网络错误：${e.message ?: "连接失败"}")
        }
    }

    override suspend fun download(
        file: CloudFile,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw IOException("尚未授权，请先扫码登录")
        try {
            // 直连流式下载: xpan/file?method=download 会 302 到 dlink, OkHttp 自动跟随
            val url = "$apiBase/xpan/file?method=download&access_token=$token" +
                    "&path=${file.path.urlEncode()}"
            val dl = client.newCall(
                Request.Builder().url(url).header("User-Agent", "pan.baidu.com").build()
            ).execute()
            val body = dl.body ?: run { dl.close(); throw IOException("下载响应为空") }
            // 若返回 JSON 而非文件流, 说明出错 (如 -6 token 失效)
            val ctype = body.contentType()?.toString().orEmpty()
            if (ctype.contains("json") || ctype.contains("text/html")) {
                val text = body.string()
                dl.close()
                val errno = runCatching { org.json.JSONObject(text).optInt("errno", -1) }.getOrDefault(-1)
                throw IOException(if (errno != -1) errnoMessage(errno) else "下载失败：服务端返回非文件内容")
            }
            if (!dl.isSuccessful) {
                dl.close()
                throw IOException("下载失败 (HTTP ${dl.code})")
            }
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var r: Int
                    var d = 0L
                    while (input.read(buf).also { r = it } != -1) {
                        out.write(buf, 0, r)
                        d += r
                        onProgress(d, total)
                        // 关键: 阻塞读取循环中检查协程是否已取消, 否则 cancel() 无法中断下载
                        if (!coroutineContext.isActive) throw CancellationException("导入已取消")
                    }
                }
            }
            dl.close()
            true
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("网络错误：${e.message ?: "下载失败"}")
        }
    }
}
