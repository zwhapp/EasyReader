package com.zeoffice.einkreader.webdav

import com.zeoffice.einkreader.domain.model.sync.ConflictPolicy
import com.zeoffice.einkreader.domain.model.sync.RemoteBookEntry
import com.zeoffice.einkreader.domain.model.sync.RemoteBookmark
import com.zeoffice.einkreader.domain.model.sync.RemoteBookmarkTombstone
import com.zeoffice.einkreader.domain.model.sync.RemoteProgress
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 同步一致性纯逻辑 — 无 Android / 无网络依赖，可直接在 JVM 上单测。
 *
 * 取代旧的「整条记录二选一」覆盖语义：
 *  - 进度：单值 LWW，胜负由 HLC 逻辑时钟判定，不依赖设备本地时钟的绝对准确度；
 *  - 书签：LWW-Element-Set，每条带稳定 id 与墓碑，删除也能跨设备传播；
 *  - 文件：内容哈希决定是否真的需要重传，避免 mtime 变化触发无谓上传。
 *
 * HLC stamp 序列化格式：wallMillis:counter:deviceId
 * 比较顺序 wallMillis → counter → deviceId（最后一维保证不同设备同时写也能确定性收敛）。
 */
object SyncMerge {

    data class Stamp(
        val wallMillis: Long,
        val counter: Int,
        val deviceId: String
    ) : Comparable<Stamp> {
        override fun compareTo(other: Stamp): Int {
            val c = wallMillis.compareTo(other.wallMillis)
            if (c != 0) return c
            val d = counter.compareTo(other.counter)
            if (d != 0) return d
            return deviceId.compareTo(other.deviceId)
        }
    }

    fun formatStamp(s: Stamp): String =
        "${s.wallMillis}:${s.counter}:${s.deviceId.replace(':', '_')}"

    fun parseStamp(raw: String?): Stamp? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(':', limit = 3)
        if (parts.size != 3) return null
        val wall = parts[0].toLongOrNull() ?: return null
        val counter = parts[1].toIntOrNull() ?: return null
        return Stamp(wall, counter, parts[2])
    }

    /** 旧数据没有 stamp 时，用已有的时间戳降级构造一个可比较的 stamp */
    fun legacyStamp(updatedAt: Long): String = "${updatedAt.coerceAtLeast(0)}:0:"

    fun effectiveStamp(raw: String?, updatedAt: Long): String =
        raw?.takeIf { it.isNotBlank() && parseStamp(it) != null }
            ?: legacyStamp(updatedAt)

    /**
     * 生成下一个 HLC：本机时钟前进则 counter 归零；时钟回拨或收到更大的远端时钟时
     * 沿用较大的 wallMillis 并递增 counter —— 保证「后写一定不比先写旧」。
     */
    fun next(nowMillis: Long, lastRaw: String?, deviceId: String): String {
        val last = parseStamp(lastRaw)
        val s = if (last == null || nowMillis > last.wallMillis) {
            Stamp(nowMillis, 0, deviceId)
        } else {
            Stamp(last.wallMillis, last.counter + 1, deviceId)
        }
        return formatStamp(s)
    }

    /** a > b 返回正数；无效 stamp 视为最旧 */
    fun compareStamps(a: String, b: String): Int {
        val sa = parseStamp(a) ?: Stamp(Long.MIN_VALUE, 0, "")
        val sb = parseStamp(b) ?: Stamp(Long.MIN_VALUE, 0, "")
        return sa.compareTo(sb)
    }

    data class ProgressMerge(
        val progress: RemoteProgress,
        val stamp: String,
        /** true 表示远端胜出，本地需要被改写 */
        val fromRemote: Boolean
    )

    /** 进度按字段整体 LWW；三类策略保留原语义，NEWER_WINS 改用 HLC 而非裸时间戳 */
    fun mergeProgress(
        local: RemoteProgress,
        localStampRaw: String?,
        localUpdatedAt: Long,
        remote: RemoteProgress,
        remoteStampRaw: String?,
        remoteUpdatedAt: Long,
        policy: ConflictPolicy
    ): ProgressMerge {
        val ls = effectiveStamp(localStampRaw, localUpdatedAt)
        val rs = effectiveStamp(remoteStampRaw, remoteUpdatedAt)
        val remoteWins = when (policy) {
            ConflictPolicy.LOCAL_WINS -> false
            ConflictPolicy.REMOTE_WINS -> true
            ConflictPolicy.NEWER_WINS -> compareStamps(rs, ls) > 0
        }
        return if (remoteWins) ProgressMerge(remote, rs, true)
        else ProgressMerge(local, ls, false)
    }

    /** 书签集合的统一内部表示：活条目与墓碑同构，便于按 id 做 LWW 归并 */
    data class BookmarkOp(
        val id: String,
        val stamp: String,
        val deleted: Boolean,
        val chapterIndex: Int = 0,
        val charOffset: Int = 0,
        val quote: String = "",
        val note: String = "",
        val createdAt: Long = 0,
        val updatedAt: Long = 0
    )

    /** 跨设备稳定 id；旧数据缺 id 时由内容派生，不依赖本地自增主键 */
    fun bookmarkId(chapterIndex: Int, charOffset: Int, quote: String): String =
        sha256Hex("$chapterIndex\u0000$charOffset\u0000$quote").take(24)

    fun fromRemoteBookmark(b: RemoteBookmark): BookmarkOp {
        val at = if (b.updatedAt > 0) b.updatedAt else b.createdAt
        return BookmarkOp(
            id = b.id.ifBlank { bookmarkId(b.chapterIndex, b.charOffset, b.quote) },
            stamp = effectiveStamp(b.stamp, at),
            deleted = false,
            chapterIndex = b.chapterIndex,
            charOffset = b.charOffset,
            quote = b.quote,
            note = b.note,
            createdAt = b.createdAt,
            updatedAt = at
        )
    }

    fun fromTombstone(t: RemoteBookmarkTombstone): BookmarkOp = BookmarkOp(
        id = t.id,
        stamp = effectiveStamp(t.stamp, t.updatedAt),
        deleted = true,
        createdAt = t.updatedAt,
        updatedAt = t.updatedAt
    )

    fun remoteOps(entry: RemoteBookEntry): List<BookmarkOp> =
        entry.bookmarks.map { fromRemoteBookmark(it) } +
            entry.bookmarkTombstones.map { fromTombstone(it) }

    fun toRemoteBookmark(op: BookmarkOp): RemoteBookmark = RemoteBookmark(
        chapterIndex = op.chapterIndex,
        charOffset = op.charOffset,
        quote = op.quote,
        note = op.note,
        createdAt = op.createdAt,
        id = op.id,
        updatedAt = op.updatedAt,
        stamp = op.stamp
    )

    fun toTombstone(op: BookmarkOp): RemoteBookmarkTombstone = RemoteBookmarkTombstone(
        id = op.id,
        stamp = op.stamp,
        updatedAt = op.updatedAt
    )

    /**
     * 拆成索引里的两个字段：活书签放 bookmarks（旧版 App 可正常读取），
     * 墓碑放 bookmarkTombstones（旧版 App 不认识会忽略，不会把删掉的书签当成新增复活）。
     */
    fun splitOps(ops: List<BookmarkOp>): Pair<List<RemoteBookmark>, List<RemoteBookmarkTombstone>> =
        ops.filter { !it.deleted }.map { toRemoteBookmark(it) }.sortedBy { it.id } to
            ops.filter { it.deleted }.map { toTombstone(it) }.sortedBy { it.id }

    /** LWW-Element-Set 归并：同 id 取 stamp 最大者；完全平局时删除优先，保证确定性 */
    fun mergeBookmarkSet(local: List<BookmarkOp>, remote: List<BookmarkOp>): List<BookmarkOp> {
        val byId = LinkedHashMap<String, BookmarkOp>()
        for (op in local + remote) {
            val cur = byId[op.id]
            if (cur == null) {
                byId[op.id] = op
                continue
            }
            val cmp = compareStamps(op.stamp, cur.stamp)
            if (cmp > 0 || (cmp == 0 && op.deleted && !cur.deleted)) byId[op.id] = op
        }
        return byId.values.sortedBy { it.id }
    }

    /**
     * 是否需要重传书籍文件：
     *  - 两边都有哈希 → 以哈希为准（mtime 变化不再触发无谓上传）；
     *  - 两边都没哈希 → 退回大小比较，兼容 v1 索引；
     *  - 只有一边有哈希 → 保守补传一次，趁机把哈希写进索引，让后续同步进入哈希模式。
     */
    fun needsFileUpload(
        localHash: String,
        localSize: Long,
        remoteHash: String,
        remoteSize: Long
    ): Boolean = when {
        localHash.isNotBlank() && remoteHash.isNotBlank() -> localHash != remoteHash
        localHash.isBlank() && remoteHash.isBlank() -> remoteSize != localSize
        else -> true
    }

    /** 书籍文件内容 sha256（流式，失败返回空串表示「哈希未知」） */
    fun fileHash(file: File): String = try {
        if (!file.isFile) "" else {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        ""
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
