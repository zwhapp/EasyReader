package com.zeoffice.einkreader.parser.epub

import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB 解析器
 * - EPUB = zip 包: META-INF/container.xml → OPF → spine 顺序
 * - 章节顺序严格按 spine itemref，而非 manifest 随意顺序
 *
 * EPUB 解析：ZIP 解包 + OPF/spine 解析 + 封面提取 (EPUB 原生支持)
 */
class EpubParser : BookParser {
    override val format = BookFormat.EPUB

    override fun parse(filePath: String): ParsedBook {
        val epub = File(filePath)
        ZipFile(epub).use { zip ->

            // 1. container.xml → OPF 路径
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw Exception("Not a valid EPUB: missing container.xml")
            val containerXml = zip.getInputStream(containerEntry).bufferedReader().readText()
            val rootFile = Regex("full-path=\"([^\"]+)\"").find(containerXml)
                ?.groupValues?.get(1)
                ?: throw Exception("container.xml 中未找到 rootfile")

            // 2. 读 OPF
            val opfEntry = zip.getEntry(rootFile)
                ?: throw Exception("OPF not found: $rootFile")
            val opfXml = zip.getInputStream(opfEntry).bufferedReader().readText()

            val title = Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>").find(opfXml)
                ?.groupValues?.get(1)?.trim()
                ?: epub.nameWithoutExtension
            val author = Regex("<dc:creator[^>]*>([\\s\\S]*?)</dc:creator>").find(opfXml)
                ?.groupValues?.get(1)?.trim() ?: ""

            // 3. manifest: id → (href, mediaType)
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
                val combined = if (href.startsWith("/")) href.removePrefix("/") else opfDir + href
                return combined.removePrefix("./")
            }

            // 4. spine 顺序
            val spineRegex = Regex("<itemref\\s[^>]*idref=\"([^\"]+)\"[^>]*/?>")
            val spineOrder = spineRegex.findAll(opfXml).map { it.groupValues[1] }.toList()

            // 5. 逐章提取文本
            val chapters = mutableListOf<ChapterContent>()
            var idx = 0
            val spineIds = if (spineOrder.isNotEmpty()) spineOrder
            else manifestItems.filter { it.mediaType.contains("html") }.map { it.id }

            for (id in spineIds) {
                val item = hrefById[id] ?: continue
                if (!item.mediaType.contains("html")) continue
                val entry = zip.getEntry(entryPath(item.href)) ?: continue
                val html = zip.getInputStream(entry).bufferedReader().readText()
                val text = stripHtml(html)
                if (text.isBlank()) continue
                val chapTitle = Regex("<title[^>]*>([\\s\\S]*?)</title>").find(html)
                    ?.groupValues?.get(1)?.trim().orEmpty()
                chapters += ChapterContent(
                    orderIndex = idx,
                    title = chapTitle.ifBlank { "Chapter ${idx + 1}" },
                    text = text,
                    startLine = 0
                )
                idx++
            }

            // 6. 封面：优先 properties="cover-image"，其次 id 含 cover 的图片
            val coverItem = manifestItems.firstOrNull { it.properties.contains("cover-image") }
                ?: manifestItems.firstOrNull {
                    it.id.lowercase().contains("cover") && it.mediaType.startsWith("image")
                }
            val coverData = coverItem?.let {
                val ce = zip.getEntry(entryPath(it.href))
                ce?.let { e -> zip.getInputStream(e).readBytes() }
            }

            return ParsedBook(
                title = title.ifBlank { epub.nameWithoutExtension },
                author = author,
                format = BookFormat.EPUB,
                chapters = chapters,
                coverData = coverData
            )
        }
    }

    /** 简易 HTML → 文本 (不依赖 Jsoup) */
    private fun stripHtml(html: String): String {
        var s = html
        s = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("</p>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return Regex("\n{3,}").replace(s, "\n\n").trim()
    }
}
