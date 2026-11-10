package com.zeoffice.einkreader.cloud

import com.zeoffice.einkreader.data.db.SyncStateEntity
import com.zeoffice.einkreader.domain.model.CloudAccount
import com.zeoffice.einkreader.domain.model.sync.LibraryIndex
import com.zeoffice.einkreader.domain.model.sync.RemoteBookEntry
import com.zeoffice.einkreader.webdav.SyncEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * 多后端同步传输层的纯逻辑回归：路径 / 分片 / 后端隔离基线 / 索引后端无关性。
 * 不触碰网络与 Android 运行时，可在 JVM 直接跑。
 */
class SyncTransportTest {

    private fun state(key: String, hash: String) =
        SyncStateEntity(bookKey = key, localHash = hash, remoteUpdatedAt = 100L, lastSyncAt = 200L)

    private fun account(id: Int, provider: String, extra: String = "") =
        CloudAccount(id = id, provider = provider, name = provider, extra = extra)

    // ---------- 路径语义 ----------

    @Test
    fun `bookPath 与 SyncEngine 的索引相对路径一致`() {
        val key = "a1b2c3"
        assertEquals(SyncEngine.bookRelPath(key, "EPUB"), SyncLayout.bookPath(key, "EPUB"))
        assertEquals("books/$key.epub", SyncLayout.bookPath(key, "EPUB"))
    }

    @Test
    fun `join 统一绝对路径拼接`() {
        assertEquals("/apps/EasyReader/books/k.txt", SyncLayout.join("/apps/EasyReader/", "/books/k.txt"))
        assertEquals("/EasyReader/library.json", SyncLayout.join("/EasyReader", "library.json"))
    }

    @Test
    fun `当前支持后端的同步根目录可读`() {
        assertEquals("/apps/EasyReader", BaiduPanClient(account(1, "baidu")).remoteRoot)
        assertEquals("/apps/MyApp", BaiduPanClient(account(1, "baidu", "/apps/MyApp")).remoteRoot)
    }

    @Test
    fun `百度同步目录非法 extra 回退默认值`() {
        assertEquals("/apps/EasyReader", BaiduPanClient(account(1, "baidu", "/wrong/path")).remoteRoot)
        assertEquals("/apps/EasyReader", BaiduPanClient(account(1, "baidu", "/apps/")).remoteRoot)
    }

    // ---------- 后端隔离基线 ----------

    @Test
    fun `scopeOf 区分 provider 与账户 id`() {
        assertEquals("webdav:1", SyncLayout.scopeOf(account(1, "webdav")))
        assertEquals("baidu:2", SyncLayout.scopeOf(account(2, "baidu")))
        assertNotEquals(
            SyncLayout.scopeOf(account(1, "webdav")),
            SyncLayout.scopeOf(account(2, "webdav"))
        )
    }

    @Test
    fun `scopedKey 可还原 bookKey`() {
        val scope = "baidu:7"
        val scoped = SyncLayout.scopedKey(scope, "abc")
        assertEquals("baidu:7|abc", scoped)
        assertEquals("abc", scoped.removePrefix("$scope|"))
    }

    @Test
    fun `baselines 兼容旧版无前缀行`() {
        val map = SyncLayout.baselines("webdav:1", listOf(state("k1", "old")))
        assertEquals("old", map["k1"]?.localHash)
    }

    @Test
    fun `baselines 中 scoped 行优先覆盖旧行`() {
        val map = SyncLayout.baselines(
            "webdav:1",
            listOf(state("k1", "legacy"), state("webdav:1|k1", "scoped"))
        )
        assertEquals(1, map.size)
        assertEquals("scoped", map["k1"]?.localHash)
    }

    @Test
    fun `baselines 忽略其它后端`() {
        val map = SyncLayout.baselines(
            "ali:3",
            listOf(state("k1", "legacy"), state("baidu:2|k2", "other"), state("ali:3|k3", "mine"))
        )
        assertEquals(setOf("k1", "k3"), map.keys)
        assertNull(map["k2"])
        assertEquals("mine", map["k3"]?.localHash)
    }

    @Test
    fun `baselines 空输入返回空`() {
        assertTrue(SyncLayout.baselines("ali:3", emptyList()).isEmpty())
    }

    // ---------- 分片与哈希 ----------

    @Test
    fun `ranges 空文件返回单个空片`() {
        assertEquals(listOf(0L to 0), SyncLayout.ranges(0L))
        assertEquals(listOf(0L to 0), SyncLayout.ranges(-1L))
    }

    @Test
    fun `ranges 小于与等于块大小都只有一片`() {
        assertEquals(listOf(0L to 123), SyncLayout.ranges(123L))
        assertEquals(
            listOf(0L to SyncLayout.BLOCK_SIZE),
            SyncLayout.ranges(SyncLayout.BLOCK_SIZE.toLong())
        )
    }

    @Test
    fun `ranges 跨块时偏移与长度连续`() {
        val size = SyncLayout.BLOCK_SIZE.toLong() * 2 + 17L
        val parts = SyncLayout.ranges(size)
        assertEquals(3, parts.size)
        assertEquals(0L, parts[0].first)
        assertEquals(SyncLayout.BLOCK_SIZE, parts[0].second)
        assertEquals(SyncLayout.BLOCK_SIZE.toLong(), parts[1].first)
        assertEquals(SyncLayout.BLOCK_SIZE.toLong() * 2, parts[2].first)
        assertEquals(17, parts[2].second)
        assertEquals(size, parts.sumOf { it.second.toLong() })
    }

    @Test
    fun `ranges 覆盖多种大小的连续性`() {
        listOf(1L, 1024L, SyncLayout.BLOCK_SIZE - 1L, SyncLayout.BLOCK_SIZE + 1L,
            SyncLayout.BLOCK_SIZE * 3L + 5L).forEach { size ->
            val parts = SyncLayout.ranges(size)
            assertEquals("size=$size", size, parts.sumOf { it.second.toLong() })
            var offset = 0L
            parts.forEach { (start, len) ->
                assertEquals("size=$size", offset, start)
                offset += len
            }
        }
    }

    @Test
    fun `md5 与 sha1 与标准向量一致`() {
        val bytes = "abc".toByteArray()
        assertEquals("900150983cd24fb0d6963f7d28e17f72", SyncLayout.md5Hex(bytes))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SyncLayout.sha1Hex(bytes))
    }

    @Test
    fun `readLimited 正常读取`() {
        val data = "hello 云端".toByteArray()
        assertArrayEquals(data, SyncLayout.readLimited(ByteArrayInputStream(data), 1024))
    }

    @Test(expected = IOException::class)
    fun `readLimited 超限立即中止`() {
        val data = ByteArray(2048) { 1 }
        SyncLayout.readLimited(ByteArrayInputStream(data), 64)
    }

    // ---------- 工厂与索引后端无关性 ----------

    @Test
    fun `工厂仅支持 WebDAV 与百度网盘`() {
        assertEquals(listOf("webdav", "baidu"), SyncTransportFactory.providers)
        listOf("webdav", "baidu").forEach { assertTrue(SyncTransportFactory.supported(it)) }
        assertFalse(SyncTransportFactory.supported("ali"))
        assertFalse(SyncTransportFactory.supported("onedrive"))
        assertFalse(SyncTransportFactory.supported(""))
    }

    @Test
    fun `工厂显示名`() {
        assertEquals("WebDAV", SyncTransportFactory.displayName("webdav"))
        assertEquals("百度网盘", SyncTransportFactory.displayName("baidu"))
    }

    @Test
    fun `v2 索引在任意后端都是同一份字节结构`() {
        val index = LibraryIndex(
            version = SyncEngine.INDEX_VERSION,
            updatedAt = 123456789L,
            books = listOf(
                RemoteBookEntry(
                    key = "key1", title = "书名", author = "作者", format = "TXT",
                    file = "books/key1.txt", size = 42L, updatedAt = 999L,
                    contentHash = "deadbeef", progressStamp = "1000:0:devA"
                )
            )
        )
        val json = SyncEngine.buildIndex(index)
        val parsed = SyncEngine.parseIndex(json)
        assertEquals(2, parsed.version)
        assertEquals(1, parsed.books.size)
        // 相对路径不带任何后端前缀：百度 /apps/...、阿里 file_id、WebDAV URL 都由传输层映射
        assertEquals("books/key1.txt", parsed.books[0].file)
        assertEquals("deadbeef", parsed.books[0].contentHash)
        assertEquals("书名", parsed.books[0].title)
    }

    @Test
    fun `v1 索引仍可被新版解析`() {
        val v1 = """{"version":1,"books":[{"key":"k","title":"旧书","author":"","format":"TXT",
            "file":"books/k.txt","size":10,"updatedAt":5,
            "progress":{"chapterIndex":0,"charOffset":3,"progressPercent":1,"lastPage":0,"readingTimeSec":0},
            "bookmarks":[]}]}""".trimIndent()
        val parsed = SyncEngine.parseIndex(v1)
        assertEquals(1, parsed.version)
        assertTrue(parsed.books[0].progressStamp.isEmpty())
        assertTrue(parsed.books[0].bookmarkTombstones.isEmpty())
    }
}
