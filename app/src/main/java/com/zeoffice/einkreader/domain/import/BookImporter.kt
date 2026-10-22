package com.zeoffice.einkreader.domain.import

import android.content.Context
import android.net.Uri
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParserFactory
import com.zeoffice.einkreader.parser.BookParser
import java.io.File

/**
 * 导入工具: 将本地 URI / 云文件 物化到应用私有目录, 并解析为统一模型。
 *
 * 所有导入最终落地到 filesDir/books/, 使解析器与 PdfRenderer 可直接按路径打开。
 */
object BookImporter {

    fun booksDir(context: Context): File {
        val dir = File(context.filesDir, "books")
        dir.mkdirs()
        return dir
    }

    fun uniqueName(context: Context, name: String): File {
        val safe = name.replace("""[^\w.\-\u4e00-\u9fa5]""".toRegex(), "_")
        var dest = File(booksDir(context), safe)
        var n = 0
        while (dest.exists()) {
            dest = File(booksDir(context), "${System.currentTimeMillis()}_$safe")
            if (++n > 20) break
        }
        return dest
    }

    /** 将 content:// 或 file:// URI 复制为应用私有文件 */
    fun materializeUri(context: Context, uri: Uri, displayName: String): File {
        val dest = uniqueName(context, displayName)
        when (uri.scheme) {
            "content" -> {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                } ?: throw Exception("无法读取内容 URI")
            }
            "file" -> {
                val src = File(uri.path ?: return dest)
                if (src.exists()) src.copyTo(dest, overwrite = true)
            }
        }
        return dest
    }

    /** 按文件路径解析为统一模型 (PDF 返回 null, 由 PdfRenderer 处理); 扩展名缺失时用 format 兜底 */
    fun parse(filePath: String, format: BookFormat): ParsedBook? {
        if (format == BookFormat.PDF) return null
        val parser = com.zeoffice.einkreader.parser.BookParserFactory
            .createForExtension(filePath.substringAfterLast('.', ""), format) ?: return null
        return runCatching { parser.parse(filePath) }.getOrNull()
    }
}
