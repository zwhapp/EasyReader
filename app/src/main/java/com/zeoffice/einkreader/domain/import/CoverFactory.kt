package com.zeoffice.einkreader.domain.import

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.zeoffice.einkreader.domain.model.BookFormat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * 封面工厂 — 为书库列表生成封面图 (存 filesDir/covers/)
 *
 * 优先级: EPUB 内嵌封面图 / PDF 首页渲染 > 按书名生成的样式化占位封面
 */
object CoverFactory {

    private const val COVER_W = 300
    private const val COVER_H = 420

    fun coversDir(context: Context): File =
        File(context.filesDir, "covers").apply { mkdirs() }

    fun coverFile(context: Context, bookId: Int): File =
        File(coversDir(context), "cover_$bookId.png")

    /** EPUB 内嵌封面字节 → 缩放落盘 */
    fun fromBytes(context: Context, bookId: Int, data: ByteArray): Boolean = try {
        val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return false
        saveScaled(src, coverFile(context, bookId))
    } catch (_: Exception) {
        false
    }

    /** PDF 首页渲染为封面 */
    fun fromPdf(srcFile: File, dest: File): Boolean = try {
        ParcelFileDescriptor.open(srcFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bmp = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    // 等比缩放并垂直居中绘制到 3:4.2 画布
                    val scale = min(
                        COVER_W.toFloat() / page.width,
                        COVER_H.toFloat() * 0.92f / page.height
                    )
                    val dw = (page.width * scale).toInt()
                    val dh = (page.height * scale).toInt()
                    val left = (COVER_W - dw) / 2
                    val top = (COVER_H - dh) / 2
                    page.render(
                        bmp,
                        android.graphics.Rect(left, top, left + dw, top + dh),
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    savePng(bmp, dest)
                }
            }
        }
    } catch (_: Exception) {
        false
    }

    /** 样式化占位封面: 底色 + 格式标签 + 书名/作者文字 */
    fun placeholder(context: Context, bookId: Int, title: String, author: String, format: BookFormat): Boolean = try {
        val bmp = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val (bg, fg) = palette(format)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = bg
        canvas.drawRoundRect(RectF(0f, 0f, COVER_W.toFloat(), COVER_H.toFloat()), 12f, 12f, paint)

        // 顶部格式标签条
        paint.color = fg
        canvas.drawRect(0f, 0f, COVER_W.toFloat(), 56f, paint)
        paint.color = bg
        paint.textSize = 30f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(format.label, COVER_W / 2f, 38f, paint)

        // 书名 (自动换行, 最多 7 行)
        paint.color = fg
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 34f
        paint.textAlign = Paint.Align.CENTER
        val lines = wrapText(paint, title, COVER_W - 40f, 7)
        var y = 140f
        for (line in lines) {
            canvas.drawText(line, COVER_W / 2f, y, paint)
            y += 44f
        }

        // 作者 (底部)
        if (author.isNotBlank()) {
            paint.textSize = 26f
            paint.typeface = Typeface.DEFAULT
            paint.color = fg
            canvas.drawText(
                wrapText(paint, author, COVER_W - 40f, 1).firstOrNull() ?: "",
                COVER_W / 2f, COVER_H - 30f, paint
            )
        }
        savePng(bmp, coverFile(context, bookId))
    } catch (_: Exception) {
        false
    }

    private fun palette(format: BookFormat): Pair<Int, Int> = when (format) {
        BookFormat.EPUB -> 0xFFCFE0F0.toInt() to 0xFF2C4E6E.toInt()
        BookFormat.TXT -> 0xFFD8E8D2.toInt() to 0xFF33502F.toInt()
        BookFormat.PDF -> 0xFFF2D8D8.toInt() to 0xFF6E2C2C.toInt()
        BookFormat.MOBI -> 0xFFE4D9EE.toInt() to 0xFF4B3268.toInt()
        else -> 0xFFF0E2CC.toInt() to 0xFF6B5220.toInt()
    }

    private fun wrapText(paint: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch == '\n') continue
            sb.append(ch)
            if (paint.measureText(sb.toString()) > maxWidth) {
                sb.setLength(sb.length - 1)
                lines.add(sb.toString())
                sb.clear().append(ch)
                if (lines.size == maxLines) break
            }
        }
        if (lines.size < maxLines && sb.isNotEmpty()) lines.add(sb.toString())
        if (lines.size == maxLines && sb.isNotEmpty() && lines.size >= maxLines) {
            val last = lines.lastOrNull() ?: return lines
            if (lines.size > maxLines) lines[maxLines - 1] = last.dropLast(1) + "…"
        }
        return lines.take(maxLines)
    }

    private fun saveScaled(src: Bitmap, dest: File): Boolean = try {
        val srcRatio = src.height.toFloat() / src.width
        val targetRatio = COVER_H.toFloat() / COVER_W
        val outW = COVER_W
        val outH = (COVER_W * srcRatio).toInt().coerceIn(COVER_H / 2, COVER_H * 2)
        val scaled = Bitmap.createScaledBitmap(src, outW, outH, true)
        savePng(scaled, dest)
    } catch (_: Exception) {
        false
    } finally {
        src.recycle()
    }

    private fun savePng(bmp: Bitmap, dest: File): Boolean = try {
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        true
    } catch (_: Exception) {
        false
    } finally {
        if (!bmp.isRecycled) bmp.recycle()
    }
}
