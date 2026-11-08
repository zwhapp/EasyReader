package com.zeoffice.einkreader.webdav

import com.zeoffice.einkreader.domain.model.sync.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SyncMergeTest {

    private fun progress(pct: Int) = RemoteProgress(
        chapterIndex = 1, charOffset = 100, progressPercent = pct, lastPage = 2, readingTimeSec = 30
    )

    // ---- HLC 逻辑时钟 ----

    @Test fun stamp_nextAdvancesAndSurvivesClockRollback() {
        val a = SyncMerge.next(1000L, null, "devA")
        assertEquals("1000:0:devA", a)
        val b = SyncMerge.next(1200L, a, "devB")
        assertEquals("1200:0:devB", b)
        // 本机时钟回拨到 900：不能生成比上一戳更旧的时钟
        val c = SyncMerge.next(900L, b, "devC")
        assertEquals("1200:1:devC", c)
        assertTrue(SyncMerge.compareStamps(c, b) > 0)
        // 收到来自"未来"的远端时钟时也要沿用其 wall 并递增
        val d = SyncMerge.next(1500L, "9999:4:devX", "devD")
        assertEquals("9999:5:devD", d)
    }

    @Test fun stamp_compareUsesWallThenCounterThenDevice() {
        assertTrue(SyncMerge.compareStamps("2000:0:a", "1000:9:z") > 0)
        assertTrue(SyncMerge.compareStamps("1000:2:a", "1000:1:z") > 0)
        assertTrue(SyncMerge.compareStamps("1000:0:z", "1000:0:a") > 0)
        assertEquals(0, SyncMerge.compareStamps("1000:0:a", "1000:0:a"))
        // 无效 stamp 视为最旧
        assertTrue(SyncMerge.compareStamps("1000:0:a", "garbage") > 0)
    }

    @Test fun effectiveStamp_fallsBackToLegacyTimestamp() {
        assertEquals("500:0:", SyncMerge.effectiveStamp("", 500))
        assertEquals("500:0:", SyncMerge.effectiveStamp("broken", 500))
        assertEquals("1000:0:devA", SyncMerge.effectiveStamp("1000:0:devA", 500))
    }

    // ---- 进度：字段级 LWW ----

    @Test fun mergeProgress_usesHlcInsteadOfDeviceWallClock() {
        // A 设备在 t=1000 写；B 设备本机时钟回拨到 900，但 HLC 递增到 1000:1
        // 旧实现用 updatedAt 比较会误判 A 更新；新实现按 HLC 选 B（真正的后写）
        val pm = SyncMerge.mergeProgress(
            local = progress(70), localStampRaw = "1000:1:devB", localUpdatedAt = 900,
            remote = progress(30), remoteStampRaw = "1000:0:devA", remoteUpdatedAt = 1000,
            policy = ConflictPolicy.NEWER_WINS
        )
        assertFalse(pm.fromRemote)
        assertEquals(70, pm.progress.progressPercent)
        assertEquals("1000:1:devB", pm.stamp)
    }

    @Test fun mergeProgress_policiesOverrideClock() {
        val local = progress(70)
        val remote = progress(30)
        val forcedRemote = SyncMerge.mergeProgress(
            local, "9999:0:a", 9999, remote, "1:0:b", 1, ConflictPolicy.REMOTE_WINS
        )
        assertTrue(forcedRemote.fromRemote)
        assertEquals(30, forcedRemote.progress.progressPercent)
        val forcedLocal = SyncMerge.mergeProgress(
            local, "1:0:a", 1, remote, "9999:0:b", 9999, ConflictPolicy.LOCAL_WINS
        )
        assertFalse(forcedLocal.fromRemote)
        assertEquals(70, forcedLocal.progress.progressPercent)
    }

    @Test fun mergeProgress_v1IndexDegradesToUpdatedAt() {
        val pm = SyncMerge.mergeProgress(
            local = progress(10), localStampRaw = "", localUpdatedAt = 100,
            remote = progress(90), remoteStampRaw = "", remoteUpdatedAt = 200,
            policy = ConflictPolicy.NEWER_WINS
        )
        assertTrue(pm.fromRemote)
        assertEquals("200:0:", pm.stamp)
    }

    // ---- 书签：LWW-Element-Set ----

    private fun op(
        id: String, stamp: String, deleted: Boolean = false, note: String = ""
    ) = SyncMerge.BookmarkOp(
        id = id, stamp = stamp, deleted = deleted,
        chapterIndex = 2, charOffset = 50, quote = "引文", note = note,
        createdAt = 100, updatedAt = 100
    )

    @Test fun bookmarkId_isStableAcrossDevices() {
        val a = SyncMerge.bookmarkId(2, 50, "引文")
        assertEquals(a, SyncMerge.bookmarkId(2, 50, "引文"))
        assertNotEquals(a, SyncMerge.bookmarkId(2, 51, "引文"))
        assertEquals(24, a.length)
    }

    @Test fun mergeBookmarkSet_unionsBookmarksFromBothDevices() {
        val merged = SyncMerge.mergeBookmarkSet(
            listOf(op("id-a", "1000:0:devA")),
            listOf(op("id-b", "1000:0:devB"))
        )
        assertEquals(2, merged.size)
        assertEquals(setOf("id-a", "id-b"), merged.map { it.id }.toSet())
    }

    @Test fun mergeBookmarkSet_deletionWinsAndIsPropagated() {
        // A 设备新增书签，B 设备删除它（删除时间更晚）
        val merged = SyncMerge.mergeBookmarkSet(
            listOf(op("id-a", "1000:0:devA")),
            listOf(op("id-a", "2000:0:devB", deleted = true))
        )
        assertEquals(1, merged.size)
        assertTrue(merged[0].deleted)
        // 墓碑必须保留在集合里，才能让第三台晚同步的设备也删掉
        val (live, tombs) = SyncMerge.splitOps(merged)
        assertTrue(live.isEmpty())
        assertEquals("id-a", tombs[0].id)
    }

    @Test fun mergeBookmarkSet_olderTombstoneDoesNotResurrectOrErase() {
        val merged = SyncMerge.mergeBookmarkSet(
            listOf(op("id-a", "3000:0:devA", note = "新批注")),
            listOf(op("id-a", "1000:0:devB", deleted = true))
        )
        assertEquals(1, merged.size)
        assertFalse(merged[0].deleted)
        assertEquals("新批注", merged[0].note)
    }

    @Test fun mergeBookmarkSet_tieBreaksDeterministically() {
        val tie = "1000:0:devX"
        val a = SyncMerge.mergeBookmarkSet(
            listOf(op("id-a", tie)), listOf(op("id-a", tie, deleted = true))
        )
        val b = SyncMerge.mergeBookmarkSet(
            listOf(op("id-a", tie, deleted = true)), listOf(op("id-a", tie))
        )
        assertEquals(a, b)
        assertTrue(a[0].deleted) // 平局时删除优先，两个方向输入结果一致
    }

    @Test fun twoDevicesChangeDifferentFields_nothingIsLost() {
        // 旧实现的典型事故：A 加书签、B 只翻页，冲突裁决二选一会把 A 的书签抹掉
        val localOps = listOf(op("bm-1", "1000:0:devA"))
        val remoteEntry = RemoteBookEntry(
            key = "k", title = "T", author = "A", format = "EPUB",
            file = "books/k.epub", updatedAt = 2000,
            progress = progress(80), progressStamp = "2000:0:devB"
        )
        val ops = SyncMerge.mergeBookmarkSet(localOps, SyncMerge.remoteOps(remoteEntry))
        val pm = SyncMerge.mergeProgress(
            progress(10), "1000:0:devA", 1000,
            remoteEntry.progress, remoteEntry.progressStamp, remoteEntry.updatedAt,
            ConflictPolicy.NEWER_WINS
        )
        assertEquals(1, ops.size)                 // 书签保住了
        assertEquals(80, pm.progress.progressPercent) // 进度也同步过来了
        assertTrue(pm.fromRemote)
    }

    @Test fun remoteOps_readsBothActiveBookmarksAndTombstones() {
        val entry = RemoteBookEntry(
            key = "k", title = "T", author = "A", format = "EPUB", file = "books/k.epub",
            bookmarks = listOf(
                RemoteBookmark(chapterIndex = 1, charOffset = 5, quote = "q", createdAt = 100)
            ),
            bookmarkTombstones = listOf(
                RemoteBookmarkTombstone(id = "gone", stamp = "3000:0:devB", updatedAt = 3000)
            )
        )
        val ops = SyncMerge.remoteOps(entry)
        assertEquals(2, ops.size)
        assertEquals(1, ops.count { !it.deleted })
        assertEquals(1, ops.count { it.deleted })
        // v1 书签缺 id 时按内容派生稳定 id
        assertEquals(SyncMerge.bookmarkId(1, 5, "q"), ops.first { !it.deleted }.id)
    }

    @Test fun splitOps_roundTripKeepsBothSets() {
        val ops = listOf(
            op("a", "1000:0:devA"),
            op("b", "2000:0:devB", deleted = true)
        )
        val (live, tombs) = SyncMerge.splitOps(ops)
        assertEquals(1, live.size)
        assertEquals(1, tombs.size)
        assertEquals(setOf("a", "b"), (live.map { it.id } + tombs.map { it.id }).toSet())
    }

    // ---- 文件内容哈希 ----

    @Test fun needsFileUpload_hashWinsOverSizeAndMtime() {
        assertFalse(SyncMerge.needsFileUpload("h1", 100, "h1", 100))
        assertTrue(SyncMerge.needsFileUpload("h2", 100, "h1", 100))
        // v1 索引或哈希未知时退回大小比较
        assertFalse(SyncMerge.needsFileUpload("", 100, "", 100))
        assertTrue(SyncMerge.needsFileUpload("", 200, "", 100))
        assertTrue(SyncMerge.needsFileUpload("h1", 100, "", 100))
    }

    @Test fun fileHash_isStableAndContentSensitive() {
        val f = File.createTempFile("easyreader-sync", ".txt")
        try {
            f.writeText("第一章 内容")
            val h1 = SyncMerge.fileHash(f)
            assertEquals(h1, SyncMerge.fileHash(f))
            assertEquals(64, h1.length)
            f.writeText("第一章 内容已改")
            assertNotEquals(h1, SyncMerge.fileHash(f))
        } finally {
            f.delete()
        }
        assertEquals("", SyncMerge.fileHash(File("/definitely/not/here")))
    }

    @Test fun indexV2_roundTripPreservesNewFields() {
        val idx = LibraryIndex(
            version = SyncEngine.INDEX_VERSION,
            updatedAt = 500,
            books = listOf(
                RemoteBookEntry(
                    key = "k", title = "T", author = "A", format = "EPUB",
                    file = "books/k.epub", size = 10, updatedAt = 400,
                    progress = progress(55),
                    bookmarks = listOf(
                        RemoteBookmark(
                            chapterIndex = 1, charOffset = 5, quote = "q", note = "n",
                            createdAt = 100, id = "bm-1", updatedAt = 200, stamp = "200:0:devA"
                        )
                    ),
                    contentHash = "abc123",
                    progressStamp = "400:0:devB",
                    bookmarkTombstones = listOf(
                        RemoteBookmarkTombstone("bm-gone", "300:0:devA", 300)
                    )
                )
            )
        )
        val parsed = SyncEngine.parseIndex(SyncEngine.buildIndex(idx))
        val b = parsed.books.single()
        assertEquals(2, parsed.version)
        assertEquals("abc123", b.contentHash)
        assertEquals("400:0:devB", b.progressStamp)
        assertEquals("bm-1", b.bookmarks.single().id)
        assertEquals("200:0:devA", b.bookmarks.single().stamp)
        assertEquals("bm-gone", b.bookmarkTombstones.single().id)
    }

    @Test fun indexV1_isStillReadableAndDegrades() {
        val v1 = """{"version":1,"updatedAt":100,"books":[{"key":"k","title":"T","author":"A","format":"EPUB","file":"books/k.epub","size":10,"updatedAt":100,"progress":{"chapterIndex":0,"charOffset":0,"progressPercent":20,"lastPage":0,"readingTimeSec":0},"bookmarks":[{"chapterIndex":1,"charOffset":5,"quote":"q","note":"","createdAt":100}]}]}"""
        val parsed = SyncEngine.parseIndex(v1)
        val b = parsed.books.single()
        assertEquals(1, parsed.version)
        assertEquals("", b.contentHash)
        assertEquals("", b.progressStamp)
        assertTrue(b.bookmarkTombstones.isEmpty())
        // 旧书签无 id / stamp，运行时必须能派生出稳定 id 与可比较的降级时钟
        val op = SyncMerge.fromRemoteBookmark(b.bookmarks.single())
        assertEquals(SyncMerge.bookmarkId(1, 5, "q"), op.id)
        assertEquals(SyncMerge.legacyStamp(100), op.stamp)
    }

    @Test fun indexV2_tombstonesNeverLeakIntoLegacyBookmarksField() {
        // 兼容性的关键约束：墓碑必须单独成字段，旧版 App 只读 bookmarks 会忽略它，
        // 否则旧版本会把「已删除」当成一条新书签插回本地，出现书签复活。
        val idx = LibraryIndex(
            version = SyncEngine.INDEX_VERSION,
            books = listOf(
                RemoteBookEntry(
                    key = "k", title = "T", author = "A", format = "EPUB",
                    file = "books/k.epub",
                    bookmarks = listOf(
                        RemoteBookmark(
                            chapterIndex = 1, charOffset = 5, quote = "q",
                            id = "live", stamp = "100:0:devA", updatedAt = 100
                        )
                    ),
                    bookmarkTombstones = listOf(
                        RemoteBookmarkTombstone("gone", "200:0:devA", 200)
                    )
                )
            )
        )
        val book = JsonParser.parseString(SyncEngine.buildIndex(idx))
            .asJsonObject.getAsJsonArray("books")[0].asJsonObject
        val legacyField = book.getAsJsonArray("bookmarks")
        assertEquals(1, legacyField.size())
        assertEquals("live", legacyField[0].asJsonObject.get("id").asString)
        assertEquals(1, book.getAsJsonArray("bookmarkTombstones").size())
        assertFalse(legacyField.toString().contains("gone"))
    }
}
