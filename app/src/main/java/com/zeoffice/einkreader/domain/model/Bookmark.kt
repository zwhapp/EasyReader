package com.zeoffice.einkreader.domain.model

data class Bookmark(
    val id: Int = 0,
    val bookId: Int,
    val chapterIndex: Int,
    val charOffset: Int,
    val quote: String,
    val note: String = "",
    // 手动新增书签必须带时间戳：旧默认 0 会让「按时间倒序」和同步冲突裁决全部失效
    val createdAt: Long = System.currentTimeMillis(),
    /** 跨设备稳定 id；为空时由 (章节,偏移,引文) 派生 */
    val uid: String = "",
    /** 最近修改时间 (毫秒) */
    val updatedAt: Long = 0,
    /** 软删除墓碑：删除要能同步到其他设备，不能物理删掉 */
    val deleted: Boolean = false,
    /** HLC 逻辑时钟 wall:counter:deviceId */
    val stamp: String = ""
)
