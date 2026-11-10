package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.webdav.WebDavSyncTransport

/** 依据账户类型构造同步传输层（导入仍走 CloudProviderFactory，两者互不干扰） */
object SyncTransportFactory {

    /**
     * 当前支持的同步方式 —— 设置页据此渲染选择器，未列入的后端不会出现在 UI。
     * 阿里云盘开放接口已不再面向个人开发者，故移除；此处不新增未经确认的后端。
     */
    val providers: List<String> = listOf("webdav", "baidu")

    fun supported(provider: String): Boolean = provider in providers

    fun create(account: CloudAccount): SyncTransport = when (account.provider) {
        "webdav" -> WebDavSyncTransport(account)
        // 网盘客户端自身实现 SyncTransport：导入能力与同步能力共用同一鉴权/HTTP 栈
        "baidu" -> BaiduPanClient(account)
        else -> throw IllegalArgumentException("不支持的同步后端：${account.provider}")
    }

    fun displayName(provider: String): String = when (provider) {
        "webdav" -> "WebDAV"
        "baidu" -> "百度网盘"
        else -> provider
    }
}
