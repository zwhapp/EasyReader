package com.zeoffice.einkreader.webdav

import com.zeoffice.einkreader.cloud.SyncLayout
import com.zeoffice.einkreader.cloud.SyncTransport
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import java.io.File
import java.io.IOException

/**
 * WebDAV 的同步传输实现 —— 相对路径映射为 `<host>/EasyReader/<relPath>` 的绝对 URL。
 * 保留原 WebDavSyncManager 的目录布局，因此老用户远端的 library.json / books/ 完全兼容。
 */
class WebDavSyncTransport(
    private val account: CloudAccount,
    private val client: WebDavClient = WebDavClient(account.host, account.username, account.password)
) : SyncTransport {

    override val backendName: String = "WebDAV"

    private val root = client.childUrl(account.host, SyncLayout.ROOT_FOLDER)
    override val remoteRoot: String = root

    private fun urlOf(relPath: String): String =
        client.childUrl(root, relPath.trimStart('/'))

    override suspend fun prepare(syncFiles: Boolean) {
        if (!client.createDir(root)) throw IOException("无法创建 WebDAV 同步目录：$root")
        if (syncFiles) {
            val books = urlOf(SyncLayout.BOOKS_DIR)
            if (!client.createDir(books)) throw IOException("无法创建 WebDAV 书籍目录：$books")
        }
    }

    override suspend fun readIndex(): String? = client.getText(urlOf(SyncLayout.INDEX_PATH))

    override suspend fun writeIndex(json: String): Boolean =
        client.uploadText(urlOf(SyncLayout.INDEX_PATH), json)

    override suspend fun uploadBook(relPath: String, file: File): Boolean =
        if (!file.exists()) false else client.upload(urlOf(relPath), file)

    override suspend fun downloadBook(relPath: String, dest: File): Boolean {
        val url = urlOf(relPath)
        return client.download(CloudFile(relPath.substringAfterLast('/'), url, false), dest)
    }
}
