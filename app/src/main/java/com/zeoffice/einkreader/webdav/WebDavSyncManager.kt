package com.zeoffice.einkreader.webdav

import android.content.Context
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
 * 流程: 确保目录 -> 拉取服务器索引 -> 计算计划 -> 按方向传输书籍文件
 *       -> 元数据按字段/集合归并并同时收敛两端 -> 写回 library.json -> 更新本地基线。
 *
 * 一致性模型（v0.4.9 起）:
 *  - 书籍文件：内容 sha256 决定是否真的需要重传，不再被 mtime 变化误触发；
 *  - 阅读进度：单值 LWW，胜负由 HLC 逻辑时钟裁决， LOCAL/REMOTE_WINS 仍可强制覆盖；
 *  - 书签笔记：LWW-Element-Set，按稳定 id 归并，删除写成墓碑以便跨设备传播。
 * 「两端都改」不再整条二选一，因此一端加书签、另一端只翻页不会互相抹掉。
 *
 * 容错: 先传文件再写索引；索引写入失败不影响已传文件，下次同步会重试补齐。
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

        // 2. 读取服务器索引 (404 -> 空索引; 解析失败 -> 中止，绝不写回，避免清空远端书库)
        val remoteIndex = try {
            client.getText(indexUrl)?.let { SyncEngine.parseIndex(it) }
        } catch (e: Exception) {
            return@withContext SyncResult(
                success = false,
                errors = listOf(e.message ?: "远端索引解析失败，已中止同步")
            )
        }
        val remoteBooks = remoteIndex?.books?.associateBy { it.key } ?: emptyMap()

        // 3. 本地视图 + 基线
        val localBooks = bookRepo.getAllBooks()
        val localViews = localBooks.map { SyncEngine.localView(it) }
        val baselines = syncStateDao.getAll().associateBy { it.bookKey }

        // 4. 计算计划 (只决定书籍文件往哪边走；元数据归并独立进行)
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
                // 两端都存在时先做纯计算归并（读本地书签，不写库）
                val merge = if (local != null && remote != null) {
                    computeMerge(local, remote, policy)
                } else null
                when (item.action) {
                    SyncAction.UPLOAD -> {
                        val lv = local ?: throw IOException("本地记录缺失")
                        val rel = SyncEngine.bookRelPath(item.key, lv.format)
                        val url = client.childUrl(root, rel)
                        val file = File(lv.filePath)
                        val needFile = syncFiles && (remote == null ||
                            SyncMerge.needsFileUpload(
                                lv.contentHash, lv.size,
                                remote.contentHash, remote.size
                            ))
                        if (needFile) {
                            if (!file.exists()) throw IOException("本地文件缺失")
                            val ok = retry { client.upload(url, file) { _, _ -> } }
                            if (!ok) throw IOException("上传文件失败")
                        }
                        pushed++
                        if (item.conflict) {
                            conflicts++
                            conflictDetails.add("《${item.title}》: 两端都有改动，已按字段归并并上传")
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
                                        progressStamp = rv.progressStamp,
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
                                if (syncBookmarks) {
                                    bookmarkRepo.replaceWithMerged(newId, SyncMerge.remoteOps(rv))
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
                                pulled++
                                if (item.conflict) {
                                    conflicts++
                                    conflictDetails.add("《${item.title}》: 两端都有改动，已归并后下载")
                                }
                            }
                        } else {
                            // 已存在: 只有文件内容真的不同才重传；进度不再被无条件覆盖
                            val file = File(local.filePath)
                            if (syncFiles && file.exists() &&
                                SyncMerge.needsFileUpload(rv.contentHash, rv.size, local.contentHash, local.size)
                            ) {
                                val ok = retry {
                                    client.download(cloudFile(rv.title, url), file) { _, _ -> }
                                }
                                if (!ok) throw IOException("更新文件失败")
                            }
                            updated++
                            if (item.conflict) {
                                conflicts++
                                conflictDetails.add("《${item.title}》: 两端都有改动，已按字段归并")
                            }
                        }
                    }

                    SyncAction.NONE -> skipped++
                }

                // 收敛: 两端都存在时，把归并结果同时写回本地与索引，避免"整条覆盖"丢数据
                if (merge != null && local != null && remote != null) {
                    applyMerge(local, merge, syncBookmarks)
                    val (live, tombs) = SyncMerge.splitOps(merge.bookmarkOps)
                    val rel = remote.file.ifBlank { SyncEngine.bookRelPath(item.key, local.format) }
                    val hash = if (item.action == SyncAction.DOWNLOAD && remote.contentHash.isNotBlank())
                        remote.contentHash else local.contentHash.ifBlank { remote.contentHash }
                    val size = if (item.action == SyncAction.DOWNLOAD && remote.size > 0)
                        remote.size else local.size
                    val mergedAt = maxOf(local.updatedAt, remote.updatedAt)
                    mergedEntries[item.key] = SyncEngine.localToEntry(
                        view = local,
                        fileRelPath = rel,
                        bookmarks = if (syncBookmarks) live else remote.bookmarks,
                        tombstones = if (syncBookmarks) tombs else remote.bookmarkTombstones,
                        progress = merge.progress,
                        progressStamp = merge.progressStamp,
                        contentHash = hash,
                        updatedAt = mergedAt,
                        size = size
                    )
                    // 基线必须反映「归并后」的本地状态，否则下次会误判为本地又改了
                    val finalBook = bookRepo.getBook(local.id)
                    syncStateDao.upsert(
                        SyncStateEntity(
                            item.key,
                            if (finalBook != null) signatureOf(finalBook, File(finalBook.filePath))
                            else local.signature,
                            mergedAt,
                            System.currentTimeMillis()
                        )
                    )
                } else if (item.action == SyncAction.UPLOAD && local != null && remote == null) {
                    // 仅本地独有: 无冲突可能，整条上传（活书签与墓碑一起写，便于他端接收删除）
                    val (live, tombs) = localBookmarkSets(local.id)
                    mergedEntries[item.key] = SyncEngine.localToEntry(
                        view = local,
                        fileRelPath = SyncEngine.bookRelPath(item.key, local.format),
                        bookmarks = if (syncBookmarks) live else emptyList(),
                        tombstones = if (syncBookmarks) tombs else emptyList()
                    )
                    syncStateDao.upsert(
                        SyncStateEntity(item.key, local.signature, local.updatedAt, System.currentTimeMillis())
                    )
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
                version = SyncEngine.INDEX_VERSION,
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

    /** 两端都存在时的归并结果 */
    private data class MergePlan(
        val progress: RemoteProgress,
        val progressStamp: String,
        val progressFromRemote: Boolean,
        val remoteUpdatedAt: Long,
        val bookmarkOps: List<SyncMerge.BookmarkOp>
    )

    private suspend fun computeMerge(
        local: LocalSyncView,
        remote: RemoteBookEntry,
        policy: ConflictPolicy
    ): MergePlan {
        val pm = SyncMerge.mergeProgress(
            local.toRemoteProgress(), local.progressStamp, local.updatedAt,
            remote.progress, remote.progressStamp, remote.updatedAt, policy
        )
        val ops = SyncMerge.mergeBookmarkSet(
            localBookmarkOps(local.id), SyncMerge.remoteOps(remote)
        )
        return MergePlan(pm.progress, pm.stamp, pm.fromRemote, remote.updatedAt, ops)
    }

    /** 把归并结果落到本地：远端胜才改写进度；书签集合始终全量对齐（含墓碑） */
    private suspend fun applyMerge(
        local: LocalSyncView,
        merge: MergePlan,
        syncBookmarks: Boolean
    ) {
        if (merge.progressFromRemote) {
            val b = bookRepo.getBook(local.id)
            if (b != null) {
                bookRepo.update(
                    b.copy(
                        chapterIndex = merge.progress.chapterIndex,
                        charOffset = merge.progress.charOffset,
                        progressPercent = merge.progress.progressPercent,
                        lastPage = merge.progress.lastPage,
                        readingTimeSec = merge.progress.readingTimeSec,
                        progressStamp = merge.progressStamp,
                        progressUpdatedAt = maxOf(b.progressUpdatedAt, merge.remoteUpdatedAt),
                        source = "webdav"
                    )
                )
            }
        }
        if (syncBookmarks) bookmarkRepo.replaceWithMerged(local.id, merge.bookmarkOps)
    }

    private suspend fun localBookmarkOps(bookId: Int): List<SyncMerge.BookmarkOp> =
        bookmarkRepo.getAllByBook(bookId).map { b ->
            val at = if (b.updatedAt > 0) b.updatedAt else b.createdAt
            SyncMerge.BookmarkOp(
                id = b.uid.ifBlank { SyncMerge.bookmarkId(b.chapterIndex, b.charOffset, b.quote) },
                stamp = SyncMerge.effectiveStamp(b.stamp, at),
                deleted = b.deleted,
                chapterIndex = b.chapterIndex,
                charOffset = b.charOffset,
                quote = b.quote,
                note = b.note,
                createdAt = b.createdAt,
                updatedAt = at
            )
        }

    private suspend fun localBookmarkSets(
        bookId: Int
    ): Pair<List<RemoteBookmark>, List<RemoteBookmarkTombstone>> =
        SyncMerge.splitOps(localBookmarkOps(bookId))

    private fun signatureOf(book: BookEntity, file: File): String =
        SyncEngine.signature(
            book.chapterIndex, book.charOffset, book.progressPercent,
            book.lastPage, book.readingTimeSec,
            if (file.exists()) file.lastModified() else 0L,
            if (file.exists()) file.length() else book.fileSize
        )

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
}
