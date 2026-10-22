package com.zeoffice.einkreader.domain.model

/**
 * 书籍格式枚举
 * - 支持主流格式：TXT / EPUB / PDF
 * - 本 App 扩展：mobi/azw/azw3
 */
enum class BookFormat(val ext: String, val label: String) {
    TXT("txt", "TXT"),
    EPUB("epub", "EPUB"),
    PDF("pdf", "PDF"),
    MOBI("mobi", "MOBI"),
    AZW("azw", "AZW"),
    AZW3("azw3", "AZW3"),
    ;

    companion object {
        fun fromFilename(name: String): BookFormat? {
            val lower = name.lowercase()
            return values().firstOrNull { lower.endsWith(".${it.ext}") }
        }

        /** .azw 本质为 EPUB 换扩展名封装，统一归到 EPUB 解析 */
        const val AZW_AS_EPUB = "azw-as-epub"
    }
}
