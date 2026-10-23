package com.zeoffice.einkreader.webdav

import com.zeoffice.einkreader.data.db.BookEntity
import com.zeoffice.einkreader.data.db.SyncStateEntity
import com.zeoffice.einkreader.domain.model.sync.*
import org.junit.Assert.*
import org.junit.Test

class WebDavSyncEngineTest {

    @Test fun keyOf_isStableAndDeterministic() {
        val a = SyncEngine.keyOf("书名", "作者", "EPUB")
        val b = SyncEngine.keyOf("书名", "作者", "EPUB")
        val c = SyncEngine.keyOf("书名", "作者", "MOBI")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(40, a.length) // SHA-1 hex
    }

    @Test fun signature_changesWithProgress() {
        val s1 = SyncEngine.signature(0, 0, 10, 0, 0, 1000L, 500L)
        val s2 = SyncEngine.signature(0, 0, 20, 0, 0, 1000L, 500L)
        assertNotEquals(s1, s2)
        val s3 = SyncEngine.signature(0, 0, 10, 0, 0, 1000L, 500L)
        assertEquals(s1, s3)
    }

    @Test fun localView_usesUpdatedAtAndSignature() {
        val book = BookEntity(
            id = 1, title = "T", author = "A", format = "EPUB",
            filePath = "/x/t.epub", fileSize = 10, importedAt = 100, source = "local",
            progressPercent = 50, progressUpdatedAt = 200
        )
        val v = SyncEngine.localView(book)
        assertEquals(SyncEngine.keyOf("T", "A", "EPUB"), v.key)
        assertEquals(200, v.updatedAt) // progressUpdatedAt 优先
    }

    private fun lv(key: String, sig: String, updatedAt: Long) = LocalSyncView(
        id = 1, key = key, signature = sig, updatedAt = updatedAt, title = "T", author = "A",
        format = "EPUB", filePath = "/x", size = 10, chapterIndex = 0, charOffset = 0,
        progressPercent = 0, lastPage = 0, readingTimeSec = 0
    )

    private fun rb(key: String, updatedAt: Long) = RemoteBookEntry(
        key = key, title = "T", author = "A", format = "EPUB",
        file = "books/$key.epub", size = 10, updatedAt = updatedAt
    )

    @Test fun plan_pushAndPull() {
        val k = SyncEngine.keyOf("T", "A", "EPUB")
        // PUSH: 本地独有 -> 上传; 服务器独有 -> 跳过
        var plan = SyncEngine.plan(listOf(lv(k, "s1", 1)), mapOf(), mapOf(), SyncDirection.PUSH, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.UPLOAD, plan[k]!!.action)
        plan = SyncEngine.plan(listOf(), mapOf(k to rb(k, 1)), mapOf(), SyncDirection.PUSH, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.NONE, plan[k]!!.action)
        // PULL: 服务器独有 -> 下载; 本地独有 -> 跳过
        plan = SyncEngine.plan(listOf(), mapOf(k to rb(k, 1)), mapOf(), SyncDirection.PULL, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.DOWNLOAD, plan[k]!!.action)
        plan = SyncEngine.plan(listOf(lv(k, "s1", 1)), mapOf(), mapOf(), SyncDirection.PULL, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.NONE, plan[k]!!.action)
    }

    @Test fun plan_twoWay_conflicts() {
        val k = SyncEngine.keyOf("T", "A", "EPUB")
        val base = SyncStateEntity(k, "sBase", 100, 0)
        // 本地独有 -> 上传
        var plan = SyncEngine.plan(listOf(lv(k, "s1", 200)), mapOf(), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.UPLOAD, plan[k]!!.action)
        // 服务器独有 -> 下载
        plan = SyncEngine.plan(listOf(), mapOf(k to rb(k, 200)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.DOWNLOAD, plan[k]!!.action)
        // 两端均未改动 -> 无操作
        plan = SyncEngine.plan(listOf(lv(k, "sBase", 50)), mapOf(k to rb(k, 100)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.NONE, plan[k]!!.action)
        // 仅本地改动 -> 上传
        plan = SyncEngine.plan(listOf(lv(k, "sNew", 50)), mapOf(k to rb(k, 100)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.UPLOAD, plan[k]!!.action)
        // 仅服务器改动 -> 下载
        plan = SyncEngine.plan(listOf(lv(k, "sBase", 50)), mapOf(k to rb(k, 300)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.DOWNLOAD, plan[k]!!.action)
        // 两端均改动 + 较新者优先: 本地较新 -> 上传(冲突)
        plan = SyncEngine.plan(listOf(lv(k, "sNew", 500)), mapOf(k to rb(k, 300)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.UPLOAD, plan[k]!!.action)
        assertTrue(plan[k]!!.conflict)
        // 两端均改动 + 较新者优先: 服务器较新 -> 下载(冲突)
        plan = SyncEngine.plan(listOf(lv(k, "sNew", 300)), mapOf(k to rb(k, 500)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncAction.DOWNLOAD, plan[k]!!.action)
        assertTrue(plan[k]!!.conflict)
        // 以本地为准 -> 上传
        plan = SyncEngine.plan(listOf(lv(k, "sNew", 1)), mapOf(k to rb(k, 999)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.LOCAL_WINS)
        assertEquals(SyncAction.UPLOAD, plan[k]!!.action)
        assertTrue(plan[k]!!.conflict)
        // 以服务器为准 -> 下载
        plan = SyncEngine.plan(listOf(lv(k, "sNew", 999)), mapOf(k to rb(k, 1)), mapOf(k to base), SyncDirection.TWO_WAY, ConflictPolicy.REMOTE_WINS)
        assertEquals(SyncAction.DOWNLOAD, plan[k]!!.action)
        assertTrue(plan[k]!!.conflict)
    }

    @Test fun index_roundTrip() {
        val idx = LibraryIndex(
            version = 1, updatedAt = 123,
            books = listOf(
                RemoteBookEntry(
                    "k1", "T", "A", "EPUB", "books/k1.epub", 10, 456,
                    RemoteProgress(2, 30, 55, 0, 120)
                )
            )
        )
        val json = SyncEngine.buildIndex(idx)
        val parsed = SyncEngine.parseIndex(json)
        assertEquals(idx.books.size, parsed.books.size)
        val b = parsed.books[0]
        assertEquals("k1", b.key)
        assertEquals(55, b.progress.progressPercent)
        assertEquals(120, b.progress.readingTimeSec)
    }

    @Test fun bookRelPath_usesFormatExt() {
        assertEquals("books/k1.epub", SyncEngine.bookRelPath("k1", "EPUB"))
        assertEquals("books/k2.azw3", SyncEngine.bookRelPath("k2", "AZW3"))
    }
}
