package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 百度网盘客户端 — 扫码授权拿 access_token 后负责 list / download（导入）与
 * 目录创建 / 分片上传 / 索引读写（同步）。
 *
 * API: https://pan.baidu.com/rest/2.0/xpan/file
 * 注意:
 *  - 百度要求请求携带 User-Agent, 否则部分接口拒绝 (errno)；
 *  - 第三方应用只能访问「我的应用数据」目录 `/apps/<应用名>/`，因此同步根目录默认
 *    `/apps/EasyReader`，可在同步页按自己开放平台的应用目录改写（存 CloudAccount.extra）；
 *  - 上传走 precreate → superfile2 分片 → create，同名文件用 rtype=3 覆盖，避免索引指向旧副本。
 */
class BaiduPanClient(
    private val account: CloudAccount,
    private val client: OkHttpClient = OkHttpClient()
) : CloudProvider, SyncTransport {

    override val provider: String = "baidu"
    override val displayName: String = "百度网盘"
    override val backendName: String = "百度网盘"

    private val apiBase = "https://pan.baidu.com/rest/2.0"
    private val uploadBase = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2"
    private val token: String get() = account.accessToken

    /** 同步根目录（必须是 /apps/ 下的应用目录，否则百度返回 errno 20020） */
    val syncRoot: String = account.extra
        .takeIf { it.startsWith("/apps/") && it.length > "/apps/".length }
        ?: "/apps/EasyReader"
    override val remoteRoot: String get() = syncRoot

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
        // 目录权限边界: 上传/下载类接口仅允许访问 /apps/<应用名>/ 目录
        20020 -> "百度网盘目录权限限制：第三方应用只能读写「我的网盘/我的应用数据」目录下的文件。" +
                "请在同步页把「同步目录」改成开放平台分配的应用目录（形如 /apps/<应用名>）"
        20011 -> "应用还在审核中：仅限前 10 个完成授权的用户测试使用"
        20012 -> "调用次数已达上限（限流）：请稍后再试，或完成应用上线审核"
        20013 -> "当前应用未开通该接口权限：请在开放平台上线审核时申请对应接口权限"
        31024 -> "没有访问权限：请检查应用的授权方式与接口权限"
        31034 -> "文件或目录不存在"
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

    /** 表单 POST 并返回原始 JSON（由调用方按 errno 分支，例如「目录已存在」属正常） */
    private suspend fun postForm(url: String, fields: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
            fields.forEach { (k, v) -> form.add(k, v) }
            val req = Request.Builder().url(url)
                .header("User-Agent", "pan.baidu.com")
                .post(form.build())
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("百度网盘服务异常 (HTTP ${resp.code})")
                runCatching { JSONObject(text) }.getOrElse { throw IOException("百度网盘响应解析失败") }
            }
        }

    private fun checkErrno(json: JSONObject) {
        val errno = json.optInt("errno", 0)
        if (errno != 0) throw IOException(errnoMessage(errno))
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
            // 若返回 JSON 而非文件流, 说明出错 (如 -6 token 失效 / -9 文件不存在)
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
        } catch (e: CancellationException) {
            // 取消必须原样透传：旧实现把它包装成 IOException，取消导入会被误报为「网络错误」
            runCatching { dest.delete() }
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("网络错误：${e.message ?: "下载失败"}")
        }
    }

    // ==================== SyncTransport：目录 / 索引 / 文件 ====================

    override suspend fun prepare(syncFiles: Boolean) {
        if (token.isBlank()) throw IOException("百度网盘尚未授权，请先扫码登录")
        createDir(syncRoot)
        if (syncFiles) createDir(SyncLayout.join(syncRoot, SyncLayout.BOOKS_DIR))
    }

    private suspend fun createDir(path: String) {
        val url = "$apiBase/xpan/file?method=create&access_token=$token"
        val json = postForm(
            url, mapOf(
                "path" to path,
                "size" to "0",
                "isdir" to "1",
                "block_list" to "[]",
                "rtype" to "3"
            )
        )
        val errno = json.optInt("errno", 0)
        // -8 = 已存在，视为成功；其余错误必须抛出，不能带着半截目录继续同步
        if (errno != 0 && errno != -8) throw IOException("创建目录失败 $path：${errnoMessage(errno)}")
    }

    override suspend fun readIndex(): String? = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw IOException("百度网盘尚未授权，请先扫码登录")
        val path = SyncLayout.join(syncRoot, SyncLayout.INDEX_PATH)
        val url = "$apiBase/xpan/file?method=download&access_token=$token" +
                "&path=${path.urlEncode()}"
        val req = Request.Builder().url(url)
            .header("User-Agent", "pan.baidu.com").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body ?: throw IOException("读取同步索引响应为空")
            val ctype = body.contentType()?.toString().orEmpty()
            if (ctype.contains("json") || ctype.contains("text/html")) {
                val text = body.string()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val errno = json?.optInt("errno", -1) ?: -1
                when (errno) {
                    -9, 31034 -> return@withContext null   // 首次同步：索引尚未创建
                    -1 -> {
                        // 少数网关把索引本体标成 application/json，只有确含 books 字段才接受
                        if (json != null && json.has("books")) return@withContext text
                        throw IOException("读取同步索引失败：服务端返回非预期内容")
                    }
                    else -> throw IOException(errnoMessage(errno))
                }
            }
            if (!resp.isSuccessful) throw IOException("读取同步索引失败 (HTTP ${resp.code})")
            val bytes = SyncLayout.readLimited(body.byteStream(), SyncLayout.MAX_INDEX_BYTES)
            String(bytes, Charsets.UTF_8)
        }
    }

    override suspend fun writeIndex(json: String): Boolean {
        val bytes = json.toByteArray(Charsets.UTF_8)
        return uploadBytes(SyncLayout.join(syncRoot, SyncLayout.INDEX_PATH), bytes.size.toLong()) { _, _ -> bytes }
    }

    override suspend fun uploadBook(relPath: String, file: File): Boolean {
        if (!file.exists()) return false
        return uploadBytes(SyncLayout.join(syncRoot, relPath), file.length()) { offset, len ->
            readBlock(file, offset, len)
        }
    }

    override suspend fun downloadBook(relPath: String, dest: File): Boolean =
        download(CloudFile(relPath.substringAfterLast('/'), SyncLayout.join(syncRoot, relPath), false), dest)

    /**
     * 分片上传：precreate → superfile2(逐片) → create。
     * rtype=3 表示同名覆盖；return_type=2 表示服务端秒传成功，无需传分片。
     */
    private suspend fun uploadBytes(
        remotePath: String,
        size: Long,
        read: (offset: Long, len: Int) -> ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw IOException("百度网盘尚未授权，请先扫码登录")
        if (size <= 0L) throw IOException("空文件不支持上传：$remotePath")
        val parts = SyncLayout.ranges(size)
        val blockList = JSONArray()
        parts.forEach { (offset, len) -> blockList.put(SyncLayout.md5Hex(read(offset, len))) }

        val pre = postForm(
            "$apiBase/xpan/file?method=precreate&access_token=$token",
            mapOf(
                "path" to remotePath,
                "size" to size.toString(),
                "isdir" to "0",
                "autoinit" to "1",
                "rtype" to "3",
                "block_list" to blockList.toString()
            )
        )
        checkErrno(pre)
        if (pre.optInt("return_type", 1) == 2) return@withContext true   // 秒传命中
        val uploadId = pre.optString("uploadid")
        if (uploadId.isBlank()) throw IOException("百度网盘未返回 uploadid，无法分片上传")

        // 服务端可跳过已存在的分片；block_list 返回待传分片序号
        val needed = pre.optJSONArray("block_list")?.let { arr ->
            (0 until arr.length()).map { arr.optInt(it) }.toSet()
        }
        parts.forEachIndexed { index, (offset, len) ->
            if (!coroutineContext.isActive) throw CancellationException("同步已取消")
            if (needed != null && index !in needed) return@forEachIndexed
            val bytes = read(offset, len)
            val url = "$uploadBase?method=upload&type=tmpfile&access_token=$token" +
                    "&path=${remotePath.urlEncode()}" +
                    "&uploadid=${uploadId.urlEncode()}&partseq=$index"
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", remotePath.substringAfterLast('/'),
                    bytes.toRequestBody("application/octet-stream".toMediaType())
                ).build()
            val req = Request.Builder().url(url)
                .header("User-Agent", "pan.baidu.com").post(multipart).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("分片上传失败 (HTTP ${resp.code})")
                val json = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw IOException("分片上传响应解析失败")
                val errno = json.optInt("errno", 0)
                if (errno != 0) throw IOException(errnoMessage(errno))
                if (json.optString("md5").isBlank()) throw IOException("分片上传响应缺少 md5")
            }
        }

        val created = postForm(
            "$apiBase/xpan/file?method=create&access_token=$token",
            mapOf(
                "path" to remotePath,
                "size" to size.toString(),
                "isdir" to "0",
                "uploadid" to uploadId,
                "block_list" to blockList.toString(),
                "rtype" to "3"
            )
        )
        checkErrno(created)
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
