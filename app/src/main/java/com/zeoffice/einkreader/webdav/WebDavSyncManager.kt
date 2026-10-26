package com.zeoffice.einkreader.webdav

import android.content.Context
import android.util.Log
import com.zeoffice.einkreader.data.db.BookEntity
import com.zeoffice.einkreader.data.db.SyncStateEntity
import com.zeoffice.einkreader.data.db.SyncStateDao
import com.zeoffice.einkreader.data.repository.BookRepository
import com.zeoffice.einkreader.data.repository.BookmarkRepository
import com.zeoffice.einkreader.domain.import.BookImporter
import com.zeoffice.einkreader.domain.import.CoverFactory
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.CloudFile
import com.zeoffice.einkreader.domain.model.Bookmark
import com.zeoffice.einkreader.domain.model.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException

/**
 * WebDAV 同步编排 (网络 + 数据库)。
 *
 * 流程: 确保目录 -> 拉取服务器索引 -> 计算计划 -> 按方向执行上传/下载
 *       (书籍文件 + 阅读进度) -> 失败重试 -> 写回合并后的 library.json -> 更新本地基线。
 *
 * 一致性保证: 先传文件再写索引; 索引写入失败不影响已传文件, 下次同步会重试补齐。
 */
class WebDavSyncManager(
    private val context: Context,
    private val bookRepo: BookRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val syncStateDao: SyncStateDao
) {
    /**
     * 执行一次同步。
     * @param onProgress 进度回调 (文案, 已完成数, 总数)
     * @return 同步结果汇总
     */
    suspend fun sync(
        account: CloudAccount,
        direction: SyncDirection,
        policy: ConflictPolicy,
        syncFiles: Boolean,
        syncBookmarks: Boolean = true,
        onProgress: suspend (msg: String, done: Int, total: Int) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        val client = WebDavClient(account.host, account.username, account.password)
        val root = client.childUrl(account.host, SyncEngine.ROOT_FOLDER)
        val booksDirUrl = client.childUrl(root, SyncEngine.BOOKS_FOLDER)
        val indexUrl = client.childUrl(root, SyncEngine.INDEX_FILE)

        // 1. 确保目录存在 (已存在时 createDir 返回 true)
        retry { client.createDir(root) }
        if (syncFiles) retry { client.createDir(booksDirUrl) }

        // 2. 读取服务器索引 (404 -> 空索引; 网络错误 -> 抛出由上层转为错误结果)
        val remoteIndex = client.getText(indexUrl)?.let { SyncEngine.parseIndex(it) }
        val remoteBooks = remoteIndex?.books?.associateBy { it.key } ?: emptyMap()

        // 3. 本地视图 + 基线
        val localBooks = bookRepo.getAllBooks()
        val localViews = localBooks.map { SyncEngine.localView(it) }
        val baselines = syncStateDao.getAll().associateBy { it.bookKey }

        // 4. 计算计划
        val plan = SyncEngine.plan(localViews, remoteBooks, baselines, direction, policy)

        var pushed = 0
        var pulled = 0
        var updated = 0
        var conflicts = 0
        var failed = 0
        var skipped = 0
        val errors = mutableListOf<String>()
        val conflictDetails = mutableListOf<String>()

        val planItems = plan.values.sortedWith(
            compareByDescending<SyncPlanItem> { it.action != SyncAction.NONE }.thenBy { it.title }
        )
        val total = planItems.size
        var done = 0
        val mergedEntries = remoteBooks.toMutableMap()

        // 5. 执行
        for (item in planItems) {
            if (!coroutineContext.isActive) throw CancellationException("同步已取消")
            val local = localViews.firstOrNull { it.key == item.key }
            val remote = remoteBooks[item.key]
            onProgress("正在同步：${item.title}", done, total)
            try {
                when (item.action) {
                    SyncAction.UPLOAD -> {
                        val lv = local ?: throw IOException("本地记录缺失")
                        val rel = SyncEngine.bookRelPath(item.key, lv.format)
                        val url = client.childUrl(root, rel)
                        val file = File(lv.filePath)
                        val needFile = syncFiles && (!file.exists() ||
                            remote == null || remote.size != lv.size || remote.updatedAt < lv.updatedAt)
                        if (needFile) {
                            if (!file.exists()) throw IOException("本地文件缺失")
                            val ok = retry {
                                client.upload(url, file) { _, _ -> }
                            }
                            if (!ok) throw IOException("上传文件失败")
                        }
                        // 书签: 开启则上传本地书签; 关闭则保留远端现有书签, 避免误清空
                        val bms = if (syncBookmarks) loadLocalBookmarks(lv.id)
                                  else (remote?.bookmarks ?: emptyList())
                        mergedEntries[item.key] = SyncEngine.localToEntry(lv, rel, bms)
                        syncStateDao.upsert(
                            SyncStateEntity(item.key, lv.signature, lv.updatedAt, System.currentTimeMillis())
                        )
                        pushed++
                        if (item.conflict) {
                            conflicts++
                            conflictDetails.add("《${item.title}》: 以本地为准（已上传覆盖服务器）")
                        }
                    }

                    SyncAction.DOWNLOAD -> {
                        val rv = remote ?: throw IOException("服务器记录缺失")
                        val url = client.childUrl(root, rv.file)
                        if (local == null) {
                            // 新书: 需下载文件 (除非关闭了书籍文件同步)
                            if (!syncFiles) {
                                errors.add("《${item.title}》未下载：请在设置中开启“书籍文件同步”")
                                failed++
                            } else {
                                val ext = SyncEngine.extOf(rv.format)
                                val dest = BookImporter.uniqueName(context, "${rv.title}.${ext}")
                                val ok = retry {
                                    client.download(cloudFile(rv.title, url), dest) { _, _ -> }
                                }
                                if (!ok) throw IOException("下载文件失败")
                                val fmt = runCatching { BookFormat.valueOf(rv.format) }.getOrDefault(BookFormat.TXT)
                                val newId = bookRepo.insert(
                                    BookEntity(
                                        title = rv.title,
                                        author = rv.author,
                                        format = fmt.name,
                                        filePath = dest.absolutePath,
                                        fileSize = dest.length(),
                                        importedAt = System.currentTimeMillis(),
                                        source = "webdav",
                                        remotePath = url,
                                        chapterCount = 0,
                                        progressUpdatedAt = rv.updatedAt,
                                        chapterIndex = rv.progress.chapterIndex,
                                        charOffset = rv.progress.charOffset,
                                        progressPercent = rv.progress.progressPercent,
                                        lastPage = rv.progress.lastPage,
                                        readingTimeSec = rv.progress.readingTimeSec
                                    )
                                ).toInt()
                                val coverFile = CoverFactory.coverFile(context, newId)
                                if (CoverFactory.placeholder(context, newId, rv.title, rv.author, fmt)) {
                                    bookRepo.getBook(newId)?.let {
                                        bookRepo.update(it.copy(coverPath = coverFile.absolutePath))
                                    }
                                }
                                // 记录同步基线: 否则下次同步会把"刚下载的书"误判为两端均改动(冲突)
                                syncStateDao.upsert(
                                    SyncStateEntity(
                                        item.key,
                                        SyncEngine.signature(
                                            rv.progress.chapterIndex, rv.progress.charOffset,
                                            rv.progress.progressPercent, rv.progress.lastPage,
                                            rv.progress.readingTimeSec, dest.lastModified(), dest.length()
                                        ),
                                        rv.updatedAt,
                                        System.currentTimeMillis()
                                    )
                                )
                                if (syncBookmarks) runCatching { mergeBookmarks(newId, rv.bookmarks) }
                                    .onFailure { Log.w("EasyReader", "合并书签失败(新书): ${it.message}") }
                                pulled++
                                if (item.conflict) {
                                    conflicts++
                                    conflictDetails.add("《${item.title}》: 以服务器为准（已下载）")
                                }
                            }
                        } else {
                            // 已存在: 若服务器文件大小不同则重新下载文件, 否则仅更新进度
                            val file = File(local.filePath)
                            if (syncFiles && file.exists() && remote.size != local.size) {
                                val ok = retry {
                                    client.download(cloudFile(rv.title, url), file) { _, _ -> }
                                }
                                if (!ok) throw IOException("更新文件失败")
                            }
                            val b = bookRepo.getBook(local.id) ?: throw IOException("本地记录缺失")
                            bookRepo.update(
                                b.copy(
                                    chapterIndex = rv.progress.chapterIndex,
                                    charOffset = rv.progress.charOffset,
                                    progressPercent = rv.progress.progressPercent,
                                    lastPage = rv.progress.lastPage,
                                    readingTimeSec = rv.progress.readingTimeSec,
                                    progressUpdatedAt = rv.updatedAt,
                                    source = "webdav",
                                    remotePath = url
                                )
                            )
                            // 基线用"同步后"的签名 (文件可能已被重新下载), 否则下次会误判为本地改动
                            syncStateDao.upsert(
                                SyncStateEntity(
                                    item.key,
                                    SyncEngine.signature(
                                        rv.progress.chapterIndex, rv.progress.charOffset,
                                        rv.progress.progressPercent, rv.progress.lastPage,
                                        rv.progress.readingTimeSec, file.lastModified(), file.length()
                                    ),
                                    rv.updatedAt,
                                    System.currentTimeMillis()
                                )
                            )
                            if (syncBookmarks) runCatching { mergeBookmarks(local.id, rv.bookmarks) }
                                .onFailure { Log.w("EasyReader", "合并书签失败: ${it.message}") }
                            updated++
                            if (item.conflict) {
                                conflicts++
                                conflictDetails.add("《${item.title}》: 以服务器为准（已更新进度）")
                            }
                        }
                    }

                    SyncAction.NONE -> {
                        // 无需改动; 若两端均存在则刷新基线, 便于后续冲突检测
                        if (local != null && remote != null) {
                            syncStateDao.upsert(
                                SyncStateEntity(item.key, local.signature, remote.updatedAt, System.currentTimeMillis())
                            )
                        }
                        skipped++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed++
                errors.add("《${item.title}》: ${e.message ?: "失败"}")
            }
            done++
        }

        // 6. 写回合并后的索引 (最后写, 保证文件先于索引到达)
        onProgress("正在保存同步索引…", total, total)
        try {
            val mergedIndex = LibraryIndex(
                version = 1,
                updatedAt = System.currentTimeMillis(),
                books = mergedEntries.values.toList()
            )
            val ok = retry { client.uploadText(indexUrl, SyncEngine.buildIndex(mergedIndex)) }
            if (!ok) throw IOException("索引写入被服务器拒绝")
        } catch (e: Throwable) {
            errors.add("索引写入失败：${e.message ?: "未知错误"}（书籍文件可能已同步，下次将自动重试）")
        }

        val success = failed == 0 && errors.isEmpty()
        val message = buildMessage(pushed, pulled, updated, conflicts, failed, skipped)
        SyncResult(
            success = success,
            pushed = pushed,
            pulled = pulled,
            updated = updated,
            conflicts = conflicts,
            failed = failed,
            skipped = skipped,
            total = total,
            errors = errors,
            conflictsDetail = conflictDetails,
            message = message
        )
    }

    private fun cloudFile(name: String, url: String) = CloudFile(name, url, isDir = false, sizeBytes = 0)

    private fun buildMessage(
        pushed: Int, pulled: Int, updated: Int, conflicts: Int, failed: Int, skipped: Int
    ): String {
        val parts = mutableListOf<String>()
        if (pushed > 0) parts.add("上传 $pushed")
        if (pulled > 0) parts.add("下载 $pulled")
        if (updated > 0) parts.add("更新 $updated")
        if (conflicts > 0) parts.add("冲突 $conflicts")
        if (skipped > 0) parts.add("无变化 $skipped")
        if (failed > 0) parts.add("失败 $failed")
        return if (parts.isEmpty()) "没有需要同步的内容" else "同步完成：${parts.joinToString("，")}"
    }

    /** 失败重试: 瞬时网络错误最多重试 3 次, 指数退避; 取消异常立即上抛 */
    private suspend fun <T> retry(times: Int = 3, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times) { i ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                delay((i + 1) * 600L)
            }
        }
        throw last ?: IOException("同步失败")
    }

    /** 本地书签 -> 远程书签 (剥离本地自增 id, 仅保留跨设备稳定字段) */
    private suspend fun loadLocalBookmarks(bookId: Int): List<RemoteBookmark> =
        bookmarkRepo.getByBook(bookId).map {
            RemoteBookmark(
                chapterIndex = it.chapterIndex,
                charOffset = it.charOffset,
                quote = it.quote,
                note = it.note,
                createdAt = it.createdAt
            )
        }

    /**
     * 把远端书签合并进本地 (追加合并语义):
     *  - 按 (章节, 偏移, 引文) 去重, 本地缺失则新增;
     *  - 已存在且远端笔记更新/非空则更新本地笔记;
     *  - 不删除本地独有书签, 保留两端批注。
     */
    private suspend fun mergeBookmarks(bookId: Int, remote: List<RemoteBookmark>) {
        if (remote.isEmpty()) return
        val local = bookmarkRepo.getByBook(bookId)
        for (rb in remote) {
            val k = bkKey(rb.chapterIndex, rb.charOffset, rb.quote)
            val existing = local.firstOrNull {
                bkKey(it.chapterIndex, it.charOffset, it.quote) == k
            }
            if (existing == null) {
                bookmarkRepo.add(
                    Bookmark(
                        bookId = bookId,
                        chapterIndex = rb.chapterIndex,
                        charOffset = rb.charOffset,
                        quote = rb.quote,
                        note = rb.note,
                        createdAt = rb.createdAt
                    )
                )
            } else if (rb.note.isNotBlank() &&
                (existing.note.isBlank() || rb.createdAt >= existing.createdAt)
            ) {
                bookmarkRepo.add(
                    existing.copy(
                        note = rb.note,
                        createdAt = if (rb.createdAt > existing.createdAt) rb.createdAt else existing.createdAt
                    )
                )
            }
        }
    }

    private fun bkKey(chapterIndex: Int, charOffset: Int, quote: String): String =
        "$chapterIndex:$charOffset:$quote"
}
