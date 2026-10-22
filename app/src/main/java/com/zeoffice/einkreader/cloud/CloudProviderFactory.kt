package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.webdav.WebDavClient

/**
 * 根据账户信息构造对应的云存储 Provider
 */
object CloudProviderFactory {
    fun create(account: CloudAccount): CloudProvider = when (account.provider) {
        "webdav" -> WebDavClient(account.host, account.username, account.password)
        "baidu" -> BaiduPanClient(account)
        "ali" -> AliyunDriveClient(account)
        else -> throw IllegalArgumentException("未知云存储类型: ${account.provider}")
    }
}
