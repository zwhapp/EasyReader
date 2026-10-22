package com.zeoffice.einkreader.cloud

import java.net.URLEncoder

/** 路径 URL 编码 (用于 WebDAV / 百度网盘 路径参数) */
fun String.urlEncode(): String = try {
    URLEncoder.encode(this, "UTF-8")
} catch (e: Exception) {
    this
}
