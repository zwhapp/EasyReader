package com.zeoffice.einkreader.webdav

import android.util.Base64
import com.zeoffice.einkreader.cloud.CloudProvider
import com.zeoffice.einkreader.domain.model.CloudFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * WebDAV 客户端 — 适配坚果云 / Nextcloud / 群晖 / 自建 WebDAV
 *
 * PROPFIND 列目录 + GET 下载, Basic 认证。实现 CloudProvider 接口。
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient = OkHttpClient.Builder().build()
) : CloudProvider {

    override val provider: String = "webdav"
    override val displayName: String = "WebDAV"

    private val authHeader: String =
        "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:propfind xmlns:D="DAV:">
          <D:prop>
            <D:displayname/>
            <D:resourcetype/>
            <D:getcontentlength/>
            <D:getlastmodified/>
          </D:prop>
        </D:propfind>
    """.trimIndent()

    override suspend fun needsAuthorize(): Boolean = false
    override suspend fun isAuthorized(): Boolean = true
    override suspend fun authorize(): Boolean = true

    /**
     * 测试连接 — PROPFIND Depth:0 验证地址 / 账号 / 密码
     * 返回结果描述: 成功或具体失败原因
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return@withContext "地址格式错误：需以 http:// 或 https:// 开头"
        }
        val url = try {
            normalize(baseUrl).toHttpUrlOrNull() ?: return@withContext "地址无效：无法解析服务器地址"
        } catch (_: Exception) {
            return@withContext "地址无效：无法解析服务器地址"
        }
        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody.toRequestBody(null))
            .header("Depth", "0")
            .header("Authorization", authHeader)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful || resp.code == 207 -> "连接成功 ✓"
                    resp.code == 401 || resp.code == 403 ->
                        "认证失败（HTTP ${resp.code}）：请检查用户名 / 密码"
                    resp.code in 500..599 ->
                        "服务器内部错误（HTTP ${resp.code}）：请确认服务端 WebDAV 已开启"
                    else ->
                        "服务器响应异常（HTTP ${resp.code}）：请确认地址指向 WebDAV 根目录"
                }
            }
        } catch (e: IOException) {
            val reason = when {
                e.message?.contains("Unable to resolve", true) == true -> "域名无法解析，请检查地址"
                e.message?.contains("timeout", true) == true -> "连接超时，请检查网络或地址"
                else -> e.message ?: "网络错误"
            }
            "无法连接服务器：$reason"
        }
    }

    override suspend fun listFiles(path: String): List<CloudFile> =
        suspendCancellableCoroutine { cont ->
            val reqPath = path.ifBlank { baseUrl }
            val url = normalize(reqPath)
            val req = Request.Builder()
                .url(url)
                .method("PROPFIND", propfindBody.toRequestBody(null))
                .header("Depth", "1")
                .header("Authorization", authHeader)
                .build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(
                        IOException("无法连接服务器：${e.message ?: "网络错误"}")
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful || response.code == 207) {
                        val list = parsePropfind(body, reqPath)
                        if (cont.isActive) cont.resume(list)
                    } else {
                        val msg = when (response.code) {
                            401, 403 -> "认证失败（HTTP ${response.code}）：请检查用户名 / 密码"
                            in 500..599 -> "服务器内部错误（HTTP ${response.code}）：请确认服务端 WebDAV 已开启"
                            else -> "服务器响应异常（HTTP ${response.code}）：请确认地址指向 WebDAV 根目录"
                        }
                        if (cont.isActive) cont.resumeWithException(IOException(msg))
                    }
                    response.close()
                }
            })
        }

    override suspend fun download(
        file: CloudFile,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = normalize(file.path)
            val req = Request.Builder().url(url).header("Authorization", authHeader).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                resp.close()
                return@withContext false
            }
            val total = resp.body?.contentLength() ?: -1L
            resp.body?.byteStream()?.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            resp.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 拼接子路径为绝对 URL */
    fun childUrl(parent: String, name: String): String {
        val p = if (parent.endsWith("/")) parent else "$parent/"
        return p + name.encodeUrl()
    }

    override fun childPath(parent: String, name: String): String = childUrl(parent, name)

    private fun normalize(url: String): String {
        if (url.startsWith("http")) return url
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return base + url.removePrefix("/")
    }

    private fun parsePropfind(xml: String, requestUrl: String): List<CloudFile> {
        val result = mutableListOf<CloudFile>()
        try {
            // 注意: 必须开启命名空间感知, 否则 DOM 的 localName 恒为 null, 解析必然失败
            val db = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder()
            val doc = db.parse(xml.byteInputStream())
            val responses = doc.getElementsByTagName("*").let { nl ->
                val list = mutableListOf<Element>()
                for (i in 0 until nl.length) {
                    val node = nl.item(i)
                    if (node is Element && node.localName.equals("response", true)) list.add(node)
                }
                list
            }
            val reqNorm = normalize(requestUrl)
            // 注意: 必须保留非默认端口, 否则拼出的 URL 打错端口 (下载失败) 且自跳过失效
            val baseHost = requestUrl.toHttpUrlOrNull()?.let { u ->
                val defaultPort = if (u.scheme == "https") 443 else 80
                if (u.port == defaultPort) "${u.scheme}://${u.host}"
                else "${u.scheme}://${u.host}:${u.port}"
            } ?: ""
            for (resp in responses) {
                val href = findText(resp, "href")?.takeIf { it.isNotBlank() } ?: continue
                val fullUrl = if (href.startsWith("http")) href
                else baseHost + (if (href.startsWith("/")) href else "/$href")
                val decodedUrl = fullUrl.replace("%20", " ").replace("%23", "#")
                // 跳过目录自身
                if (decodedUrl.trimEnd('/') == reqNorm.trimEnd('/') ||
                    fullUrl.trimEnd('/') == reqNorm.trimEnd('/')
                ) continue
                val propstat = firstChildByLocalName(resp, "propstat")
                val prop = propstat?.let { firstChildByLocalName(it, "prop") } ?: resp
                val displayName = findText(prop, "displayname")?.takeIf { it.isNotBlank() }
                    ?: decodedUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
                        .ifBlank { decodedUrl }
                val isDir = hasCollection(prop)
                val size = findText(prop, "getcontentlength")?.toLongOrNull() ?: 0L
                val lastMod = findText(prop, "getlastmodified")
                result.add(
                    CloudFile(
                        name = displayName,
                        path = decodedUrl,
                        isDir = isDir,
                        sizeBytes = size,
                        lastModified = lastMod
                    )
                )
            }
        } catch (e: Exception) {
            throw IOException("目录解析失败：${e.message ?: "响应格式异常"}")
        }
        return result
    }

    private fun hasCollection(prop: Element): Boolean {
        val rt = firstChildByLocalName(prop, "resourcetype") ?: return false
        return firstChildByLocalName(rt, "collection") != null
    }

    private fun firstChildByLocalName(parent: Element, local: String): Element? {
        val nl = parent.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.localName.equals(local, true)) return n
        }
        return null
    }

    private fun findText(parent: Element, local: String): String? {
        val el = firstChildByLocalName(parent, local) ?: return null
        return el.textContent?.trim()
    }

    private fun String.decodeUrl(): String = try {
        URLDecoder.decode(this, "UTF-8")
    } catch (e: Exception) {
        this
    }

    private fun String.encodeUrl(): String = this
        .replace(" ", "%20")
        .replace("#", "%23")
        .replace("?", "%3F")
        .replace("&", "%26")
}
