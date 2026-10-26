package com.zeoffice.einkreader.domain.model.sync

/**
 * WebDAV 同步领域模型 — 书籍文件与阅读进度的双向同步。
 *
 * 身份键: sha1(标题\0作者\0格式), 跨设备稳定匹配同一本书。
 * 服务器布局: <baseUrl>EasyReader/library.json (索引) + EasyReader/books/<key>.<ext> (书籍文件)。
 */

/** 同步方向 */
enum class SyncDirection {
    /** 双向合并 (默认) */
    TWO_WAY,
    /** 仅上传本地 -> 服务器 */
    PUSH,
    /** 仅下载服务器 -> 本地 */
    PULL
}

/** 冲突解决策略 (双向同步两端均改动时生效) */
enum class ConflictPolicy {
    /** 以修改时间较新者为准 (默认) */
    NEWER_WINS,
    /** 始终以本地为准 */
    LOCAL_WINS,
    /** 始终以服务器为准 */
    REMOTE_WINS
}

/** 单条同步动作 */
enum class SyncAction {
    UPLOAD,
    DOWNLOAD,
    NONE
}

/** 远程书籍条目进度 (library.json 内嵌) */
data class RemoteProgress(
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val progressPercent: Int = 0,
    val lastPage: Int = 0,
    val readingTimeSec: Long = 0
)

/**
 * 远程书签 / 笔记 (library.json 内嵌, 随所属书籍条目一起同步)。
 * 不包含本地自增 id —— 跨设备用 (chapterIndex,charOffset,quote) 作稳定去重键。
 */
data class RemoteBookmark(
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val quote: String = "",
    val note: String = "",
    val createdAt: Long = 0
)

/** 远程书籍条目 (library.json 的一项) */
data class RemoteBookEntry(
    val key: String,
    val title: String,
    val author: String,
    val format: String,
    /** 相对服务器根目录的路径, 如 books/<key>.epub */
    val file: String,
    val size: Long = 0,
    /** 本条最近更新时间 (毫秒) — 用于冲突检测 */
    val updatedAt: Long = 0,
    val progress: RemoteProgress = RemoteProgress(),
    /** 该书签与笔记列表 (随书籍条目一并同步, 追加合并语义) */
    val bookmarks: List<RemoteBookmark> = emptyList()
)

/** 服务器书库索引 (library.json) */
data class LibraryIndex(
    val version: Int = 1,
    val updatedAt: Long = 0,
    val books: List<RemoteBookEntry> = emptyList()
)

/** 本地书籍同步视图 (由 BookEntity + 文件信息派生) */
data class LocalSyncView(
    val id: Int,
    val key: String,
    val signature: String,
    val updatedAt: Long,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val size: Long,
    val chapterIndex: Int,
    val charOffset: Int,
    val progressPercent: Int,
    val lastPage: Int,
    val readingTimeSec: Long
) {
    fun toRemoteProgress() = RemoteProgress(
        chapterIndex, charOffset, progressPercent, lastPage, readingTimeSec
    )
}

/** 单条同步计划 */
data class SyncPlanItem(
    val key: String,
    val action: SyncAction,
    val conflict: Boolean,
    val title: String
)

/** 同步结果汇总 */
data class SyncResult(
    val success: Boolean,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val updated: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val total: Int = 0,
    val errors: List<String> = emptyList(),
    val conflictsDetail: List<String> = emptyList(),
    val message: String = ""
)

/** 同步 UI 状态 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    data class Syncing(val msg: String, val done: Int, val total: Int) : SyncStatus()
    data class Done(val result: SyncResult) : SyncStatus()
    data class Error(val msg: String) : SyncStatus()
}
