package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.webdav.WebDavSyncTransport

/** 依据账户类型构造同步传输层（导入仍走 CloudProviderFactory，两者互不干扰） */
object SyncTransportFactory {

    fun supported(provider: String): Boolean =
        provider == "webdav" || provider == "baidu" || provider == "ali"

    fun create(account: CloudAccount): SyncTransport = when (account.provider) {
        "webdav" -> WebDavSyncTransport(account)
        // 两个网盘客户端自身实现 SyncTransport：导入能力与同步能力共用同一鉴权/HTTP 栈
        "baidu" -> BaiduPanClient(account)
        "ali" -> AliyunDriveClient(account)
        else -> throw IllegalArgumentException("不支持的同步后端：${account.provider}")
    }

    fun displayName(provider: String): String = when (provider) {
        "webdav" -> "WebDAV"
        "baidu" -> "百度网盘"
        "ali" -> "阿里云盘"
        else -> provider
    }
}
