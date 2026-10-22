package com.zeoffice.einkreader.parser

import android.content.Context
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.parser.azw.AzwParser
import com.zeoffice.einkreader.parser.epub.EpubParser
import com.zeoffice.einkreader.parser.mobi.MobiParser
import com.zeoffice.einkreader.parser.pdf.PdfParser
import com.zeoffice.einkreader.parser.txt.TxtParser

/**
 * 解析器工厂 — 按扩展名路由到对应解析器
 */
object BookParserFactory {

    fun createForFile(filePath: String, context: Context? = null): BookParser? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return createForExtension(ext)
    }

    /** 按扩展名/已知格式路由; 扩展名缺失时可用 format 兜底 */
    fun createForExtension(ext: String, format: BookFormat? = null): BookParser? {
        createByExt(ext)?.let { return it }
        return when (format) {
            BookFormat.TXT -> TxtParser()
            BookFormat.EPUB -> EpubParser()
            BookFormat.MOBI -> MobiParser()
            BookFormat.AZW -> AzwParser()
            else -> null
        }
    }

    private fun createByExt(ext: String): BookParser? = when (ext) {
        "txt" -> TxtParser()
        "epub" -> EpubParser()
        "pdf" -> PdfParser()
        "mobi" -> MobiParser()
        "azw", "azw3" -> AzwParser()
        else -> null
    }

    fun createForUri(uri: android.net.Uri, context: Context): BookParser? {
        val ext = uri.lastPathSegment?.substringAfterLast(".")?.lowercase() ?: ""
        return when (ext) {
            "pdf" -> PdfParser()
            else -> createForFile(uri.path ?: "", context)
        }
    }

    fun supportedExtensions(): List<String> =
        listOf("txt", "epub", "pdf", "mobi", "azw", "azw3")
}