package com.zeoffice.einkreader.domain.model.sync

/**
 * WebDAV 同步领域模型 — 书籍文件与阅读进度/书签的双向同步。
 *
 * 身份键: sha1(标题\0作者\0格式), 跨设备稳定匹配同一本书。
 * 服务器布局: <baseUrl>EasyReader/library.json (索引) + EasyReader/books/<key>.<ext> (书籍文件)。
 *
 * 索引 v2 在 v1 基础上新增的可选字段：
 *  - contentHash / progressStamp：内容哈希与进度 HLC，新旧版本互相可读；
 *  - bookmarks 的 id / updatedAt / stamp 与单独的 bookmarkTombstones：书签按 LWW-Element-Set 归并。
 * 旧版 App 读 v2 会忽略未知字段；新版读 v1 会把缺失字段降级为按时间戳比较。
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

/** 冲突解决策略 (仅用于「进度」这种单值数据的裁决；书签集合始终按 id 归并，不二选一) */
enum class ConflictPolicy {
    /** 以 HLC 逻辑时钟较新者为准 (默认) */
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
 * 远程书签 / 笔记 (library.json 内嵌)。
 * v1 用 (chapterIndex,charOffset,quote) 去重；v2 起带稳定 id / HLC stamp，
 * 使「编辑笔记」与「删除」都能跨设备传播。缺省值保证读 v1 索引不报错。
 */
data class RemoteBookmark(
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val quote: String = "",
    val note: String = "",
    val createdAt: Long = 0,
    /** 跨设备稳定 id；旧索引为空时由内容派生 */
    val id: String = "",
    /** 最近修改时间 (毫秒) */
    val updatedAt: Long = 0,
    /** HLC 逻辑时钟 wall:counter:deviceId */
    val stamp: String = ""
)

/**
 * 书签墓碑。单独成字段而非混进 bookmarks，是为了让旧版 App 直接忽略它 ——
 * 否则旧版本会把墓碑当成一条普通书签重新插入本地，导致「删掉的书签又回来了」。
 */
data class RemoteBookmarkTombstone(
    val id: String = "",
    val stamp: String = "",
    val updatedAt: Long = 0
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
    /** 活书签列表 (随书籍条目一并同步) */
    val bookmarks: List<RemoteBookmark> = emptyList(),
    /** 书籍文件内容 sha256；空表示 v1 索引或哈希未知 */
    val contentHash: String = "",
    /** 进度的 HLC 逻辑时钟；空表示 v1 索引，降级用 updatedAt */
    val progressStamp: String = "",
    /** 已删除书签的墓碑 (仅 v2) */
    val bookmarkTombstones: List<RemoteBookmarkTombstone> = emptyList()
)

/** 服务器书库索引 (library.json) */
data class LibraryIndex(
    val version: Int = 2,
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
    val readingTimeSec: Long,
    /** 本地进度的 HLC 逻辑时钟 */
    val progressStamp: String = "",
    /** 书籍文件内容 sha256；文件不存在时为空 */
    val contentHash: String = ""
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
