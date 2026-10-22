package com.zeoffice.einkreader.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.zeoffice.einkreader.domain.model.ReadingSettings

/**
 * 文本阅读视图 (Canvas 渲染) — 支持分页、点击翻页、长按取词。
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var paginator: Paginator? = null
    private var pages: List<ReadPage> = emptyList()
    var pageIndex = 0
        private set

    var bgColor: Int = Color.WHITE
    var textColor: Int = Color.BLACK
    var justify = true
    var onPageTurn: ((delta: Int) -> Unit)? = null
    var onWordTap: ((word: String, clickIndexInRun: Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

    private var settings: ReadingSettings = ReadingSettings()
    private var chapterText: String = ""
    private var chapterIndex: Int = 0

    private val density: Float get() = resources.displayMetrics.density
    private val scaledDensity: Float get() = resources.displayMetrics.scaledDensity
    private val marginPx: Int get() = (settings.marginHorizontalDp * density).toInt()
    private val marginTopPx: Int get() = (settings.marginVerticalDp * density).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f

    fun configure(settings: ReadingSettings, bg: Int, text: Int) {
        this.settings = settings
        this.bgColor = bg
        this.textColor = text
        textPaint.color = text
        textPaint.textSize = settings.fontSizeSp * scaledDensity
        textPaint.typeface = when (settings.fontFamily) {
            "serif", "song" -> Typeface.SERIF
            else -> Typeface.DEFAULT
        }
        rebuild()
    }

    fun setChapter(index: Int, text: String) {
        this.chapterIndex = index
        this.chapterText = text
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0 || chapterText.isEmpty()) return
        val contentW = (width - 2 * marginPx).coerceAtLeast(10)
        val contentH = (height - 2 * marginTopPx).coerceAtLeast(10)
        val lineH = settings.fontSizeSp * settings.lineSpacing * scaledDensity
        val paraS = settings.paragraphSpacing * density
        paginator = Paginator(textPaint, contentW, contentH, lineH, paraS)
        pages = paginator!!.paginate(chapterIndex, chapterText)
        if (pageIndex >= pages.size) pageIndex = (pages.size - 1).coerceAtLeast(0)
        invalidate()
    }

    fun setPage(i: Int) {
        pageIndex = i.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        invalidate()
    }

    fun gotoOffset(offset: Int) {
        if (pages.isEmpty()) return
        setPage(Paginator.pageIndexForOffset(pages, offset))
    }

    val pageCount: Int get() = pages.size
    val currentPageIndex: Int get() = pageIndex
    val totalPages: Int get() = pages.size

    /** 当前页首字符在章节内的偏移 (用于进度保存/书签) */
    fun currentCharOffset(): Int = pages.getOrNull(pageIndex)?.startOffset ?: 0

    /** 当前页首行文本 (用于书签摘要) */
    fun currentLineText(): String =
        pages.getOrNull(pageIndex)?.lines?.firstOrNull()?.text ?: ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val page = pages.getOrNull(pageIndex) ?: return
        for (line in page.lines) {
            if (justify && line.text.isNotBlank()) {
                drawJustified(canvas, line)
            } else {
                canvas.drawText(
                    line.text,
                    marginPx.toFloat(),
                    line.baseline + marginTopPx,
                    textPaint
                )
            }
        }
    }

    private fun drawJustified(canvas: Canvas, line: LaidLine) {
        val text = line.text
        val natural = textPaint.measureText(text)
        val contentW = (width - 2 * marginPx).coerceAtLeast(1)
        val slack = contentW - natural
        // 仅对"自然换行"的整行两端对齐; 段落末行/短行 (空白明显) 保持左对齐
        val charW = textPaint.measureText("国").coerceAtLeast(1f)
        if (slack > charW || text.length <= 1) {
            canvas.drawText(text, marginPx.toFloat(), line.baseline + marginTopPx, textPaint)
            return
        }
        val gaps = text.length - 1
        val per = slack / gaps
        var x = marginPx.toFloat()
        for (ch in text) {
            canvas.drawText(ch.toString(), x, line.baseline + marginTopPx, textPaint)
            x += textPaint.measureText(ch.toString()) + per
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                longPress = Runnable {
                    longPressFired = true
                    val p = pages.getOrNull(pageIndex) ?: return@Runnable
                    val hit = paginator?.wordAt(p, downX - marginPx, downY - marginTopPx)
                    if (hit != null && hit.word.isNotBlank())
                        onWordTap?.invoke(hit.word, hit.clickIndexInRun)
                }
                handler.postDelayed(longPress!!, 350)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > 12 || kotlin.math.abs(event.y - downY) > 12) {
                    longPress?.let { handler.removeCallbacks(it) }
                }
            }
            MotionEvent.ACTION_UP -> {
                longPress?.let { handler.removeCallbacks(it) }
                if (longPressFired) return true  // 长按已取词, 不再触发点击翻页
                if (kotlin.math.abs(event.x - downX) < 12 && kotlin.math.abs(event.y - downY) < 12) {
                    val third = width / 3f
                    when {
                        downX < third -> onPageTurn?.invoke(-1)
                        downX > width - third -> onPageTurn?.invoke(1)
                        else -> onCenterTap?.invoke()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
