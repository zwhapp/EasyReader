package com.zeoffice.einkreader.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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

    companion object {
        /** 下载后的 APK 文件名 (应用私有外部目录, 对应 file_paths.xml 的 external-files-path) */
        const val UPDATE_APK_NAME = "einkreader_update.apk"
    }

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

    /**
     * 下载 APK (OkHttp, 同步执行于 IO 线程)。
     *
     * 说明: 早期实现用 DownloadManager, 但它是异步的 —— enqueue 后立即返回,
     * 文件尚未落盘就被送去签名校验, 导致校验必然失败、安装永远不触发;
     * 且 getSystemService 在部分 ROM 上可能返回 null 引发崩溃。
     * 改为直接下载可在返回前确保文件已完整写入。
     *
     * @param onProgress (已下载字节, 总字节) 总字节为 -1 表示长度未知
     * @return 已下载完成的 APK 文件
     */
    suspend fun downloadApk(
        apkUrl: String,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, UPDATE_APK_NAME)
        if (file.exists()) file.delete()

        val request = okhttp3.Request.Builder().url(apkUrl).build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw Exception("下载失败：${e.message ?: "请检查网络"}")
        }
        response.use { r ->
            if (!r.isSuccessful) throw Exception("下载失败（HTTP ${r.code}）")
            val body = r.body ?: throw Exception("下载内容为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
        }
        file
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

    /**
     * 触发安装。
     *
     * 关键点: 本类的 context 是 Application, 从非 Activity context 启动 Activity
     * 必须带 FLAG_ACTIVITY_NEW_TASK, 否则抛 AndroidRuntimeException 导致应用崩溃
     * (这正是旧版"点更新就退出"的直接原因)。
     */
    fun installApk(apkFile: File): InstallResult {
        if (!apkFile.exists()) return InstallResult.Failed("安装包不存在，请重新下载")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 引导去设置页授权 (必须带 NEW_TASK)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (startActivitySafely(intent)) InstallResult.NeedPermission
            else InstallResult.Failed("无法打开授权页面，请在系统设置中允许本应用「安装未知应用」")
        }

        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            } catch (e: Exception) {
                return InstallResult.Failed("无法生成安装地址：${e.message ?: "未知错误"}")
            }
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (startActivitySafely(intent)) InstallResult.Started
        else InstallResult.Failed("没有可处理安装的应用")
    }

    /** 安全启动 Activity: 先检查可解析性, 再捕获一切异常, 避免崩溃 */
    private fun startActivitySafely(intent: Intent): Boolean = try {
        if (intent.resolveActivity(context.packageManager) == null) false
        else {
            context.startActivity(intent)
            true
        }
    } catch (_: Exception) {
        false
    }
}

data class GitHubRelease(
    val tag: String,
    val name: String,
    val body: String,
    val publishedAt: String
)

/** 安装引导结果 */
sealed class InstallResult {
    /** 已唤起系统安装界面 */
    object Started : InstallResult()
    /** 未授权「安装未知应用」, 已跳转到授权页 */
    object NeedPermission : InstallResult()
    /** 失败 (含具体原因, 用于界面提示而不是崩溃) */
    data class Failed(val msg: String) : InstallResult()
}
