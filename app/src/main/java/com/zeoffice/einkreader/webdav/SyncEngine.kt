package com.zeoffice.einkreader.webdav

import com.zeoffice.einkreader.data.db.BookEntity
import com.zeoffice.einkreader.data.db.SyncStateEntity
import com.zeoffice.einkreader.domain.model.sync.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * WebDAV 同步核心纯逻辑 (无网络/无 Android 依赖, 便于单元测试):
 *  - 身份键 / 本地指纹
 *  - 双向同步冲突决策 plan()
 *  - library.json 索引的解析与构建
 */
object SyncEngine {
    const val ROOT_FOLDER = "EasyReader"
    const val INDEX_FILE = "library.json"
    const val BOOKS_FOLDER = "books"

    private val GSON: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** 稳定身份键: sha1(标题\0作者\0格式) — 跨设备稳定匹配同一本书 */
    fun keyOf(title: String, author: String, format: String): String =
        sha1("${title}\u0000${author}\u0000${format}")

    /** 格式 -> 文件扩展名 */
    fun extOf(format: String): String = format.lowercase()

    /** 本地指纹: 进度字段 + 文件 mtime/size, 用于检测"本地是否改动" */
    fun signature(
        chapterIndex: Int,
        charOffset: Int,
        progressPercent: Int,
        lastPage: Int,
        readingTimeSec: Long,
        fileMtime: Long,
        fileSize: Long
    ): String =
        sha1("$progressPercent|$chapterIndex|$charOffset|$lastPage|$readingTimeSec|$fileMtime|$fileSize")

    /** 本地最近更新时间 (进度更新优先, 否则导入时间) */
    fun localUpdatedAt(book: BookEntity): Long =
        if (book.progressUpdatedAt > 0) book.progressUpdatedAt else book.importedAt

    /** 由 BookEntity 派生本地同步视图 */
    fun localView(book: BookEntity): LocalSyncView {
        val file = File(book.filePath)
        val exists = file.exists()
        val mtime = if (exists) file.lastModified() else 0L
        val size = if (exists) file.length() else book.fileSize
        return LocalSyncView(
            id = book.id,
            key = keyOf(book.title, book.author, book.format),
            signature = signature(
                book.chapterIndex, book.charOffset, book.progressPercent,
                book.lastPage, book.readingTimeSec, mtime, size
            ),
            updatedAt = localUpdatedAt(book),
            title = book.title,
            author = book.author,
            format = book.format,
            filePath = book.filePath,
            size = size,
            chapterIndex = book.chapterIndex,
            charOffset = book.charOffset,
            progressPercent = book.progressPercent,
            lastPage = book.lastPage,
            readingTimeSec = book.readingTimeSec
        )
    }

    /**
     * 计算单条同步计划 (双向/上传/下载 + 冲突决策)。
     * 冲突判定依赖上次同步的基线: 本地指纹与服务器 updatedAt。
     */
    fun plan(
        locals: List<LocalSyncView>,
        remotes: Map<String, RemoteBookEntry>,
        baselines: Map<String, SyncStateEntity>,
        direction: SyncDirection,
        policy: ConflictPolicy
    ): Map<String, SyncPlanItem> {
        val keys = (locals.map { it.key } + remotes.keys).toSet()
        val result = mutableMapOf<String, SyncPlanItem>()
        for (key in keys) {
            val local = locals.firstOrNull { it.key == key }
            val remote = remotes[key]
            val base = baselines[key]
            val title = local?.title ?: remote?.title ?: key
            val item = when (direction) {
                SyncDirection.PUSH -> {
                    if (local != null) SyncPlanItem(key, SyncAction.UPLOAD, false, title)
                    else SyncPlanItem(key, SyncAction.NONE, false, title)
                }
                SyncDirection.PULL -> {
                    if (remote != null) SyncPlanItem(key, SyncAction.DOWNLOAD, false, title)
                    else SyncPlanItem(key, SyncAction.NONE, false, title)
                }
                SyncDirection.TWO_WAY -> when {
                    local == null -> SyncPlanItem(key, SyncAction.DOWNLOAD, false, title)
                    remote == null -> SyncPlanItem(key, SyncAction.UPLOAD, false, title)
                    else -> {
                        val localChanged = local.signature != base?.localHash
                        val remoteChanged = remote.updatedAt != base?.remoteUpdatedAt
                        when {
                            !localChanged && !remoteChanged ->
                                SyncPlanItem(key, SyncAction.NONE, false, title)
                            localChanged && !remoteChanged ->
                                SyncPlanItem(key, SyncAction.UPLOAD, false, title)
                            !localChanged && remoteChanged ->
                                SyncPlanItem(key, SyncAction.DOWNLOAD, false, title)
                            else -> { // 两端均改动 -> 冲突
                                when (policy) {
                                    ConflictPolicy.NEWER_WINS ->
                                        if (local.updatedAt >= remote.updatedAt)
                                            SyncPlanItem(key, SyncAction.UPLOAD, true, title)
                                        else SyncPlanItem(key, SyncAction.DOWNLOAD, true, title)
                                    ConflictPolicy.LOCAL_WINS ->
                                        SyncPlanItem(key, SyncAction.UPLOAD, true, title)
                                    ConflictPolicy.REMOTE_WINS ->
                                        SyncPlanItem(key, SyncAction.DOWNLOAD, true, title)
                                }
                            }
                        }
                    }
                }
            }
            result[key] = item
        }
        return result
    }

    /** 本地视图 -> 远程条目 (用于上传后写回索引) */
    fun localToEntry(
        view: LocalSyncView,
        fileRelPath: String,
        bookmarks: List<RemoteBookmark> = emptyList()
    ): RemoteBookEntry =
        RemoteBookEntry(
            key = view.key,
            title = view.title,
            author = view.author,
            format = view.format,
            file = fileRelPath,
            size = view.size,
            updatedAt = view.updatedAt,
            progress = view.toRemoteProgress(),
            bookmarks = bookmarks
        )

    /** 生成书籍文件相对路径 (相对同步根目录) */
    fun bookRelPath(key: String, format: String): String =
        "$BOOKS_FOLDER/$key.${extOf(format)}"

    /**
     * 解析服务器索引 JSON。
     *
     * 采用 JsonParser 手动解析, 不依赖 Gson 的泛型反射反序列化:
     * R8 会剥离匿名 TypeToken 子类的泛型签名 (运行时抛 IllegalStateException:
     * "TypeToken must be created with a type argument"), 也可能破坏字段泛型;
     * 手动逐字段读取完全绕开泛型签名, 在混淆包中稳定可用。
     */
    fun parseIndex(json: String): LibraryIndex {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val booksArr = root.getAsJsonArray("books")
            val books = ArrayList<RemoteBookEntry>()
            if (booksArr != null) {
                for (el in booksArr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val p = o.getAsJsonObject("progress")
                    val bmArr = o.getAsJsonArray("bookmarks")
                    val bookmarks = ArrayList<RemoteBookmark>()
                    if (bmArr != null) {
                        for (bel in bmArr) {
                            if (!bel.isJsonObject) continue
                            val b = bel.asJsonObject
                            bookmarks.add(
                                RemoteBookmark(
                                    chapterIndex = intOf(b, "chapterIndex"),
                                    charOffset = intOf(b, "charOffset"),
                                    quote = strOf(b, "quote"),
                                    note = strOf(b, "note"),
                                    createdAt = longOf(b, "createdAt")
                                )
                            )
                        }
                    }
                    books.add(
                        RemoteBookEntry(
                            key = strOf(o, "key"),
                            title = strOf(o, "title"),
                            author = strOf(o, "author"),
                            format = strOf(o, "format").ifBlank { "TXT" },
                            file = strOf(o, "file"),
                            size = longOf(o, "size"),
                            updatedAt = longOf(o, "updatedAt"),
                            progress = RemoteProgress(
                                chapterIndex = intOf(p, "chapterIndex"),
                                charOffset = intOf(p, "charOffset"),
                                progressPercent = intOf(p, "progressPercent"),
                                lastPage = intOf(p, "lastPage"),
                                readingTimeSec = longOf(p, "readingTimeSec")
                            ),
                            bookmarks = bookmarks
                        )
                    )
                }
            }
            val ver = intOf(root, "version")
            LibraryIndex(
                version = if (ver == 0) 1 else ver,
                updatedAt = longOf(root, "updatedAt"),
                books = books
            )
        } catch (e: Exception) {
            LibraryIndex()
        }
    }

    private fun strOf(o: JsonObject?, name: String): String =
        o?.get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun intOf(o: JsonObject?, name: String): Int =
        o?.get(name)?.takeIf { !it.isJsonNull }?.asInt ?: 0

    private fun longOf(o: JsonObject?, name: String): Long =
        o?.get(name)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

    /** 序列化索引为 JSON */
    fun buildIndex(index: LibraryIndex): String = GSON.toJson(index)

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
