package com.zeoffice.einkreader.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 同步基线 — 记录每本书上次同步时的本地指纹与服务器 updatedAt,
 * 用于检测"自上次同步以来本地/服务器是否发生改动", 支撑双向同步的冲突判定。
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val bookKey: String,
    /** 上次同步时本地进度+文件指纹 (sha1) */
    val localHash: String,
    /** 上次同步时服务器条目的 updatedAt */
    val remoteUpdatedAt: Long,
    /** 上次同步时间 (毫秒) */
    val lastSyncAt: Long
)
