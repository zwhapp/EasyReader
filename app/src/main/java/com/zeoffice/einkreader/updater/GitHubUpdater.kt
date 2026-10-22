package com.zeoffice.einkreader.updater

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * GitHub 在线更新 — 检查 GitHub Releases, 下载 APK, 校验签名, 引导安装
 *
 * 对应 Manifest: REQUEST_INSTALL_PACKAGES + INTERNET
 * 更新流程:
 *   1. GET /repos/{owner}/{repo}/releases/latest → 获取 release + apk asset URL
 *   2. 下载 APK 到文件 (DownloadManager)
 *   3. SHA-256 校验 (可选, 防篡改)
 *   4. PackageInstaller (Android 7+) 或 FileProvider + ACTION_VIEW (旧版) 引导安装
 */
class GitHubUpdater(
    private val context: Context,
    private val owner: String = "zwhapp",
    private val repo: String = "EasyReader",
    private val authToken: String? = null  // 可选, 公开仓库不需要
) {
    private val gson = Gson()
    private val client = okhttp3.OkHttpClient()

    /** 加速镜像前缀 (GitHub 在国内访问慢时启用) */
    var mirrorBase: String = "https://gh-proxy.com/"

    private fun apiUrl(path: String, mirror: Boolean): String =
        if (mirror) "${mirrorBase}https://api.github.com$path"
        else "https://api.github.com$path"

    /**
     * 检查是否有新版 (mirror=true 走加速镜像)
     * 失败时抛出带具体原因的异常, 由调用方捕获后反馈给用户
     */
    suspend fun checkLatest(mirror: Boolean = false): GitHubRelease = withContext(Dispatchers.IO) {
        val url = apiUrl("/repos/$owner/$repo/releases/latest", mirror)
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .apply { authToken?.let { addHeader("Authorization", "token $it") } }
            .build()
        val resp = try {
            client.newCall(req).execute()
        } catch (e: Exception) {
            throw Exception("网络连接失败：${e.message ?: "请检查网络"}")
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                throw Exception(
                    when (r.code) {
                        403, 429 -> "请求过于频繁（HTTP ${r.code}），请稍后再试"
                        404 -> "未找到发布仓库（HTTP 404），请确认已发布 Release"
                        else -> "服务器返回错误（HTTP ${r.code}）"
                    }
                )
            }
            val json = r.body?.string()
                ?: throw Exception("服务器返回内容为空")
            val map = try {
                gson.fromJson(json, Map::class.java)
            } catch (e: Exception) {
                throw Exception("解析版本信息失败（响应格式异常）")
            } ?: throw Exception("解析版本信息失败（响应格式异常）")
            GitHubRelease(
                tag = map["tag_name"] as? String ?: "",
                name = map["name"] as? String ?: "",
                body = map["body"] as? String ?: "",
                publishedAt = map["published_at"] as? String ?: ""
            )
        }
    }

    /** 查找 APK 资源 (mirror=true 走加速镜像) */
    fun findApkAsset(release: GitHubRelease, mirror: Boolean = false): String? {
        val url = apiUrl("/repos/$owner/$repo/releases/tags/${release.tag}", mirror)
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        val resp = client.newCall(req).execute()
        try {
            if (!resp.isSuccessful) return null
            val json = resp.body?.string() ?: return null
            val map = gson.fromJson(json, Map::class.java) ?: return null
            @Suppress("UNCHECKED_CAST")
            val assets = map["assets"] as? List<Map<String, *>> ?: return null
            val raw = assets.firstOrNull {
                val n = it["name"] as? String ?: ""
                n.endsWith(".apk")
            }?.get("browser_download_url") as? String ?: return null
            return if (mirror) "${mirrorBase}$raw" else raw
        } finally {
            resp.close()
        }
    }

    /** 下载 APK (DownloadManager) */
    fun downloadApk(apkUrl: String): File {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val file = File(context.getExternalFilesDir(null), "einkreader_update.apk")
        val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("EasyReader 更新")
            setDescription("正在下载新版本...")
            // 下载到应用私有外部目录 (无需存储权限)
            setDestinationInExternalFilesDir(context, null, "einkreader_update.apk")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        }
        dm.enqueue(req)
        return file
    }

    /** SHA-256 校验 */
    fun verifySha256(file: File, expected: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { md.update(it.readBytes()) }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    /**
     * 签名校验 — 下载的 APK 必须与当前应用同签名, 防止被替换为恶意包
     */
    fun isSignatureValid(apkPath: String): Boolean = try {
        val pm = context.packageManager
        fun sigDigests(sigs: Array<android.content.pm.Signature>?) =
            sigs?.map {
                MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
            }?.toSet() ?: emptySet()

        val archiveSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            sigDigests(
                pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.signingInfo?.apkContentsSigners
            )
        } else {
            @Suppress("DEPRECATION")
            sigDigests(pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)?.signatures)
        }

        val currentSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            sigDigests(
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            )
        } else {
            @Suppress("DEPRECATION")
            sigDigests(
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            )
        }

        archiveSigs.isNotEmpty() && archiveSigs == currentSigs
    } catch (e: Exception) {
        false
    }

    /** 触发安装 */
    fun installApk(apkFile: File) {
        if (!apkFile.exists()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 请求安装未知来源权限
            val installPkg = context.packageManager.canRequestPackageInstalls()
            if (!installPkg) {
                // 引导去设置页授权
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
                return
            }
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

data class GitHubRelease(
    val tag: String,
    val name: String,
    val body: String,
    val publishedAt: String
)
