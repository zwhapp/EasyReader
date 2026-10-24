package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.BuildConfig

/**
 * 百度网盘开放平台凭据。
 *
 * 为避免将第三方密钥写入源码仓库（公开分享/发布会带来凭据泄露风险），凭据不再硬编码，
 * 而是改由 local.properties（已 gitignore）在编译期经 BuildConfig 注入：
 *   BAIDU_APP_ID / BAIDU_APP_KEY / BAIDU_SECRET_KEY
 * 获取方式：到百度网盘开放平台 (https://pan.baidu.com/union) 创建应用，把分配的值填入
 * local.properties 对应字段即可；未配置时本功能自动禁用（[configured] == false），不影响其它功能。
 */
object BaiduConfig {
    val APP_ID: String = BuildConfig.BAIDU_APP_ID
    val APP_KEY: String = BuildConfig.BAIDU_APP_KEY
    val SECRET_KEY: String = BuildConfig.BAIDU_SECRET_KEY

    val configured: Boolean get() = APP_KEY.isNotBlank() && SECRET_KEY.isNotBlank()
}
