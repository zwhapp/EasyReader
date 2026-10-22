package com.zeoffice.einkreader.parser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.zeoffice.einkreader.domain.model.BookFormat
import com.zeoffice.einkreader.domain.model.ChapterContent
import com.zeoffice.einkreader.domain.model.ParsedBook
import com.zeoffice.einkreader.parser.BookParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * PDF 解析器 — 基于框架自带 PdfRenderer (API 21+，零外部依赖)
 * 提取首页渲染图作为封面、页数用于书库预览
 * 实际逐页阅读走 EInkPdfReaderView
 */
class PdfParser : BookParser {
    override val format = BookFormat.PDF

    override fun parse(filePath: String): ParsedBook {
        val uri = Uri.parse(filePath)
        val title = filePath.substringAfterLast('/').substringBeforeLast('.')

        // PdfParser 无 Context 无法打开 Uri，由 ViewModel 走 parsePdfFromUri
        // 此路径兜底：仅文件名 + 标题
        return ParsedBook(
            title = title,
            author = "",
            format = BookFormat.PDF,
            chapters = listOf(
                ChapterContent(
                    orderIndex = 0,
                    title = title,
                    text = "PDF 文档",
                    startLine = 0
                )
            ),
            coverData = null
        )
    }
}

/**
 * 从 Uri 解析 PDF（需要 Context），供 ViewModel 调用：
 * 渲染首页为封面 + 读取总页数
 */
suspend fun Context.parsePdfFromUri(uri: Uri): ParsedBook = withContext(Dispatchers.IO) {
    val title = uri.lastPathSegment?.substringBeforeLast('.') ?: "PDF"
    val fd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
        ?: throw Exception("无法打开 PDF 文件")

    fd.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val pageCount = renderer.pageCount

            // 渲染首页缩略图作为封面
            val coverBytes = if (pageCount > 0) {
                try {
                    renderer.openPage(0).use { page ->
                        val scale = 220f / page.width.coerceAtLeast(1)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        bmp.recycle()
                        out.toByteArray()
                    }
                } catch (e: Exception) {
                    null
                }
            } else null

            ParsedBook(
                title = title,
                author = "",
                format = BookFormat.PDF,
                chapters = listOf(
                    ChapterContent(
                        orderIndex = 0,
                        title = title,
                        text = "PDF 文档，共 $pageCount 页",
                        startLine = 0
                    )
                ),
                coverData = coverBytes
            )
        }
    }
}
