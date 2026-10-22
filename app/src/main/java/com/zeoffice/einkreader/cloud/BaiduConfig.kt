package com.zeoffice.einkreader.cloud

/**
 * 百度网盘开放平台凭据 — 由开发者在此处配置一次, 终端用户无需填写任何 AppKey。
 *
 * 获取方式: 到百度网盘开放平台 (https://pan.baidu.com/union) 创建应用,
 * 将分配的 AppKey / SecretKey 填到下面两个常量即可。用户侧入口为「扫码授权登录」。
 */
object BaiduConfig {
    /** 开放平台 AppID (OAuth 流程本身不使用, 备档) */
    const val APP_ID = "124505722"

    const val APP_KEY = "jyYJsFawvkZ9s6sCoeAGLzQ2Cl54shy3"
    const val SECRET_KEY = "rRn5dMtF93COOOsfY54sH2TNX2UHx4Vq"

    val configured: Boolean get() = APP_KEY.isNotBlank() && SECRET_KEY.isNotBlank()
}
