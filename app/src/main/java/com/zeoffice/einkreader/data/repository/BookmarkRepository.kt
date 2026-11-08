package com.zeoffice.einkreader.data.repository

import com.zeoffice.einkreader.data.db.EinkDatabase
import com.zeoffice.einkreader.data.toDomain
import com.zeoffice.einkreader.data.toEntity
import com.zeoffice.einkreader.data.db.BookmarkEntity
import com.zeoffice.einkreader.domain.model.Bookmark
import com.zeoffice.einkreader.webdav.SyncMerge

class BookmarkRepository(private val db: EinkDatabase) {
    private val dao = db.bookmarkDao()

    /** UI 用：仅活书签 */
    suspend fun getByBook(bookId: Int): List<Bookmark> = dao.getByBook(bookId).map { it.toDomain() }

    /** 同步用：含墓碑 */
    suspend fun getAllByBook(bookId: Int): List<Bookmark> =
        dao.getAllByBook(bookId).map { it.toDomain() }

    suspend fun add(b: Bookmark) = dao.insert(b.toEntity())

    /** 新增或「复活」同 uid 的书签：删掉后重新添加不应产生重复行 */
    suspend fun addOrRevive(b: Bookmark) {
        val uid = b.uid.ifBlank { SyncMerge.bookmarkId(b.chapterIndex, b.charOffset, b.quote) }
        val existing = dao.getAllByBook(b.bookId).firstOrNull { it.effectiveUid() == uid }
        dao.insert(
            b.toEntity().copy(
                id = existing?.id ?: 0,
                uid = uid,
                deleted = false
            )
        )
    }

    /** 软删除：写 HLC 墓碑，让删除能传播到其他设备（不再物理删除） */
    suspend fun remove(id: Int, nowMillis: Long, deviceId: String) {
        val b = dao.getById(id) ?: return
        val last = b.stamp.ifBlank {
            SyncMerge.legacyStamp(if (b.updatedAt > 0) b.updatedAt else b.createdAt)
        }
        dao.markDeleted(id, nowMillis, SyncMerge.next(nowMillis, last, deviceId))
    }

    /** 同步归并后把 LWW-Element-Set 的结果全量落库（含墓碑） */
    suspend fun replaceWithMerged(bookId: Int, ops: List<SyncMerge.BookmarkOp>) {
        // 旧版本可能重复添加过同一条书签：同一 uid 只保留一行，避免归并后出现重复展示
        val grouped = dao.getAllByBook(bookId).groupBy { it.effectiveUid() }
        grouped.values.flatMap { it.drop(1) }.forEach { dao.deleteById(it.id) }
        val existing = grouped.mapValues { it.value.first() }
        for (op in ops) {
            val cur = existing[op.id]
            val entity = BookmarkEntity(
                id = cur?.id ?: 0,
                bookId = bookId,
                chapterIndex = op.chapterIndex,
                charOffset = op.charOffset,
                quote = op.quote,
                note = op.note,
                createdAt = if (op.createdAt > 0) op.createdAt else op.updatedAt,
                uid = op.id,
                updatedAt = op.updatedAt,
                deleted = op.deleted,
                stamp = op.stamp
            )
            if (cur == null || cur.stamp != op.stamp || cur.deleted != op.deleted ||
                cur.note != op.note
            ) dao.insert(entity)
        }
    }

    private fun BookmarkEntity.effectiveUid(): String =
        uid.ifBlank { SyncMerge.bookmarkId(chapterIndex, charOffset, quote) }
}
