package com.zeoffice.einkreader.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 设备码扫码授权结果 */
data class BaiduDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val qrCodeUrl: String,
    val intervalSec: Long,
    val expiresInSeconds: Long
)

/** 轮询令牌结果 */
sealed class BaiduTokenPoll {
    /** 用户尚未扫码确认, 继续轮询 */
    object Pending : BaiduTokenPoll()

    /** 授权成功 */
    data class Done(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long) : BaiduTokenPoll()

    /** 设备码过期 / 被拒绝, 需重新生成二维码 */
    data class Expired(val msg: String) : BaiduTokenPoll()

    /** 其它错误 */
    data class Error(val msg: String) : BaiduTokenPoll()
}

/**
 * 百度网盘 OAuth 2.0 设备码(扫码)授权客户端。
 *
 * 流程: requestDeviceCode() → 展示二维码/用户码 → 用户用百度 App 扫码确认 →
 * 循环 pollToken() 直至授权成功。AppKey/SecretKey 内置于应用, 用户无需填写。
 */
class BaiduOAuth(private val client: OkHttpClient = OkHttpClient()) {

    /** 第一步: 请求设备码 (含二维码图片地址) */
    suspend fun requestDeviceCode(): BaiduDeviceCode = withContext(Dispatchers.IO) {
        if (!BaiduConfig.configured) {
            throw IllegalStateException("应用尚未配置百度网盘 AppKey (BaiduConfig.kt)，配置后即可扫码登录")
        }
        val body = FormBody.Builder()
            .add("response_type", "device_code")
            .add("client_id", BaiduConfig.APP_KEY)
            .add("scope", "basic,netdisk")
            .build()
        val req = Request.Builder()
            .url("https://openapi.baidu.com/oauth/2.0/device/code")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IllegalStateException("授权服务响应异常 (HTTP ${resp.code})")
            // OAuth 错误: {"error":"invalid_client","error_description":"..."}
            json.optString("error").takeIf { it.isNotBlank() }?.let { err ->
                throw IllegalStateException(describeOAuthError(err, json.optString("error_description")))
            }
            BaiduDeviceCode(
                deviceCode = json.getString("device_code"),
                userCode = json.optString("user_code"),
                verificationUrl = json.optString("verification_url", "https://openapi.baidu.com/device"),
                qrCodeUrl = json.optString("qrcode_url"),
                intervalSec = json.optLong("interval", 5L),
                expiresInSeconds = json.optLong("expires_in", 300L)
            )
        }
    }

    /** 第二步: 轮询令牌 (按返回的 interval 间隔调用) */
    suspend fun pollToken(deviceCode: String): BaiduTokenPoll = withContext(Dispatchers.IO) {
        // 百度使用非标准的 grant_type=device_token; 若不支持则回退到 RFC 8628 的 URN 格式
        val r = tokenRequest(deviceCode, "device_token")
        if (r is BaiduTokenPoll.Error && r.msg.contains("unsupported_grant_type")) {
            tokenRequest(deviceCode, "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
        } else r
    }

    private suspend fun tokenRequest(deviceCode: String, grantType: String): BaiduTokenPoll =
        withContext(Dispatchers.IO) {
            val url = "https://openapi.baidu.com/oauth/2.0/token" +
                    "?grant_type=$grantType" +
                    "&code=${deviceCode.urlEncode()}" +
                    "&client_id=${BaiduConfig.APP_KEY.urlEncode()}" +
                    "&client_secret=${BaiduConfig.SECRET_KEY.urlEncode()}"
            val req = Request.Builder().url(url).get().build()
            try {
                client.newCall(req).execute().use { resp ->
                    val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                        ?: return@withContext BaiduTokenPoll.Error("授权服务响应异常 (HTTP ${resp.code})")
                    val err = json.optString("error")
                    return@withContext when {
                        err.isBlank() -> BaiduTokenPoll.Done(
                            accessToken = json.getString("access_token"),
                            refreshToken = json.optString("refresh_token"),
                            expiresInSeconds = json.optLong("expires_in", 2592000L)
                        )
                        err == "authorization_pending" -> BaiduTokenPoll.Pending
                        err == "slow_down" -> BaiduTokenPoll.Pending
                        err == "authorization_expired" || err == "expired_token" ->
                            BaiduTokenPoll.Expired("二维码已过期，请刷新后重新扫码")
                        else -> BaiduTokenPoll.Error(describeOAuthError(err, json.optString("error_description")))
                    }
                }
            } catch (e: Exception) {
                BaiduTokenPoll.Error("网络错误：${e.message ?: "连接失败"}")
            }
        }

    private fun describeOAuthError(err: String, desc: String): String = when (err) {
        "invalid_client" -> "应用凭据无效 (invalid_client)：请检查 BaiduConfig 中的 AppKey / SecretKey"
        "invalid_request" -> "授权请求参数错误${if (desc.isNotBlank()) "：$desc" else ""}"
        else -> if (desc.isNotBlank()) "授权失败：$desc ($err)" else "授权失败 ($err)"
    }
}
