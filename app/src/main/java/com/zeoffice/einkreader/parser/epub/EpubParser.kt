package com.zeoffice.einkreader.parser.epub

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import com.zeoffice.einkreader.parser.HtmlText
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * EPUB 解析器：ZIP 解包 + container.xml → OPF → spine 顺序 → XHTML 文本。
 *
 * v0.4.8 修复：
 *  - href 按 URI 解码并规范化 ".." / "./"，合法 EPUB 的章节与封面不再被静默丢弃
 *  - 每个 ZIP 条目流用 .use 关闭，并限制单条目解压大小（防 zip bomb）
 *  - HTML → 文本改用共用的 HtmlText（块级标签换行、数字实体、单次解码）
 */
class EpubParser : BookParser {
    override val format = BookFormat.EPUB

    companion object {
        private const val MAX_ENTRY_BYTES = 12L * 1024 * 1024
    }

    override fun parse(filePath: String): ParsedBook {
        val epub = File(filePath)
        ZipFile(epub).use { zip ->

            // 1. container.xml → OPF 路径
            val containerXml = readEntry(zip, "META-INF/container.xml")
                ?: throw Exception("Not a valid EPUB: missing container.xml")
            val rootFile = normalizePath(
                decode(Regex("full-path=\"([^\"]+)\"").find(containerXml)
                    ?.groupValues?.get(1)
                    ?: throw Exception("container.xml 中未找到 rootfile"))
            )

            // 2. 读 OPF
            val opfXml = readEntry(zip, rootFile)
                ?: throw Exception("OPF not found: $rootFile")

            val title = HtmlText.decodeEntities(
                Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>").find(opfXml)
                    ?.groupValues?.get(1)?.trim().orEmpty()
            ).ifBlank { epub.nameWithoutExtension }
            val author = HtmlText.decodeEntities(
                Regex("<dc:creator[^>]*>([\\s\\S]*?)</dc:creator>").find(opfXml)
                    ?.groupValues?.get(1)?.trim().orEmpty()
            )

            // 3. manifest: id → (href, mediaType, properties)
            data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)
            val itemRegex = Regex("<item\\s[^>]*>")
            val manifestItems = itemRegex.findAll(opfXml).mapNotNull { m ->
                val tag = m.value
                val id = Regex("id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val href = Regex("href=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
                val mediaType = Regex("media-type=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
                val props = Regex("properties=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
                ManifestItem(id, href, mediaType, props)
            }.toList()

            val hrefById = manifestItems.associateBy { it.id }
            val opfDir = if (rootFile.contains('/')) rootFile.substringBeforeLast('/') + "/" else ""

            fun entryPath(href: String): String {
                val clean = decode(href).substringBefore('#').substringBefore('?')
                val combined = if (clean.startsWith("/")) clean.removePrefix("/") else opfDir + clean
                return normalizePath(combined)
            }

            // 4. spine 顺序
            val spineRegex = Regex("<itemref\\s[^>]*idref=\"([^\"]+)\"[^>]*/?>")
            val spineOrder = spineRegex.findAll(opfXml).map { it.groupValues[1] }.toList()
            val spineIds = if (spineOrder.isNotEmpty()) spineOrder
            else manifestItems.filter { it.mediaType.contains("html") }.map { it.id }

            // 5. 逐章提取文本
            val chapters = mutableListOf<ChapterContent>()
            for (id in spineIds) {
                val item = hrefById[id] ?: continue
                if (!item.mediaType.contains("html")) continue
                val html = readEntry(zip, entryPath(item.href)) ?: continue
                val chapTitle = HtmlText.extractTitle(html)
                val text = HtmlText.toPlainText(html)
                if (text.isBlank()) continue
                chapters += ChapterContent(
                    orderIndex = chapters.size,
                    title = chapTitle.ifBlank { "Chapter ${chapters.size + 1}" },
                    text = text,
                    startLine = 0
                )
            }

            // 6. 封面：优先 properties="cover-image"，其次 id 含 cover 的图片
            val coverItem = manifestItems.firstOrNull { it.properties.contains("cover-image") }
                ?: manifestItems.firstOrNull {
                    it.id.lowercase().contains("cover") && it.mediaType.startsWith("image")
                }
            val coverData = coverItem?.let { item ->
                val entry = zip.getEntry(entryPath(item.href))
                if (entry != null && entry.size in 1..MAX_ENTRY_BYTES) {
                    zip.getInputStream(entry).use { it.readBytes() }
                } else null
            }

            return ParsedBook(
                title = title,
                author = author,
                format = BookFormat.EPUB,
                chapters = chapters,
                coverData = coverData
            )
        }
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > MAX_ENTRY_BYTES) return null
        return zip.getInputStream(entry).use { input ->
            val bytes = input.readBytes()
            // EPUB XHTML 绝大多数为 UTF-8；非法字节交给解码器替换而不是抛异常
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }
    }

    private fun decode(href: String): String = try {
        URLDecoder.decode(href, "UTF-8")
    } catch (e: Exception) {
        href
    }

    /** 规范化 ZIP 内路径：去掉 ./、解析 ../，兼容 OPF 里常见的相对路径 */
    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
