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
 *
 * v0.4.8 排版自适应：
 *  - 屏幕尺寸 / 横竖屏 / 分屏 / 系统字号变化后按新内容区重新分页
 *  - 重排时以「当前页首字符偏移」为锚还原位置，而不是保留绝对页码
 *    （旋转后每页行数改变，保留页码会跳到完全不同的内容）
 *  - 大屏限制最大行宽并居中；行高不小于字体度量高；段落首行缩进
 *  - 每次重排只发生一次（show() 是单一入口），结束后通过 onLayoutChanged
 *    把真实页码同步回 Compose 状态，避免页码脱节导致翻页错乱
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

    /** 重排结束回调：(当前页, 总页数, 当前页首字符偏移) */
    var onLayoutChanged: ((pageIndex: Int, pageCount: Int, charOffset: Int) -> Unit)? = null

    private var settings: ReadingSettings = ReadingSettings()
    private var chapterText: String = ""
    private var chapterIndex: Int = -1
    private var contentBox = TextLayout.ContentBox(0, 0, 1, 1)
    /** View 尚未 measure (width=0) 时先记下目标偏移，onSizeChanged 后用它定位 */
    private var pendingOffset: Int? = null

    private val density: Float get() = resources.displayMetrics.density
    private val scaledDensity: Float get() = resources.displayMetrics.scaledDensity
    private val marginXPx: Int get() = (settings.marginHorizontalDp * density).toInt()
    private val marginYPx: Int get() = (settings.marginVerticalDp * density).toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var longPress: Runnable? = null
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f

    /** 只更新画笔与配色，不触发分页；重排统一由 show() / onSizeChanged 发起。 */
    fun configure(settings: ReadingSettings, bg: Int, text: Int, eink: Boolean = false) {
        applyStyle(settings, bg, text, eink)
    }

    private fun applyStyle(s: ReadingSettings, bg: Int, text: Int, eink: Boolean) {
        this.settings = s
        this.bgColor = bg
        this.textColor = text
        // 两端对齐会改变每个字符的绘制 x，必须在分页前确定并交给 Paginator 统一布局。
        justify = s.textAlign != "left"
        textPaint.color = text
        textPaint.textSize = s.fontSizeSp * scaledDensity
        // 墨水屏灰度有限：关闭抗锯齿与抖动，避免灰边残影
        textPaint.isAntiAlias = !eink
        textPaint.isDither = !eink
        textPaint.typeface = when (s.fontFamily) {
            "serif", "song" -> Typeface.SERIF
            else -> Typeface.DEFAULT
        }
    }

    /**
     * 阅读页单一入口：一次性套用设置 + 设置章节 + 分页 + 定位。
     *
     * @param targetOffset 重排后跳转到的章节内字符偏移；null 表示按当前锚点自动还原
     * @param gotoLast 跳到本章最后一页（从上一章往前翻时使用）
     */
    fun show(
        index: Int,
        text: String,
        s: ReadingSettings,
        bg: Int,
        fg: Int,
        eink: Boolean,
        targetOffset: Int? = null,
        gotoLast: Boolean = false
    ) {
        applyStyle(s, bg, fg, eink)
        val sameChapter = index == chapterIndex && text == chapterText
        if (targetOffset != null) pendingOffset = targetOffset
        chapterIndex = index
        chapterText = text
        rebuild(preservePosition = sameChapter && targetOffset == null, notify = false)
        when {
            targetOffset != null -> gotoOffset(targetOffset)
            gotoLast -> setPage((pages.size - 1).coerceAtLeast(0))
            !sameChapter -> setPage(0)
        }
        notifyLayout()
    }

    /** 兼容旧调用：仅换章（不主动通知，由调用方自行 setPage） */
    fun setChapter(index: Int, text: String) {
        val same = index == chapterIndex && text == chapterText
        chapterIndex = index
        chapterText = text
        rebuild(preservePosition = same, notify = false)
    }

    private fun rebuild(preservePosition: Boolean, notify: Boolean) {
        // 空章节必须清掉上一章的排版结果，否则屏幕上会继续显示旧章文字。
        if (width == 0 || height == 0 || chapterText.isEmpty()) {
            pages = emptyList()
            paginator = null
            pageIndex = 0
            invalidate()
            // View 还没测量完：不要通知上层把锚点清成 0，pendingOffset 会保留到下次重排
            return
        }

        val anchor = if (preservePosition) pages.getOrNull(pageIndex)?.startOffset ?: -1 else -1

        val box = TextLayout.contentBox(
            viewWidth = width,
            viewHeight = height,
            marginXPx = marginXPx,
            marginYPx = marginYPx,
            maxLineWidthPx = (TextLayout.MAX_LINE_WIDTH_DP * density).toInt()
        )
        contentBox = box

        val fm = textPaint.fontMetrics
        val lineH = TextLayout.lineHeight(
            textPaint.textSize, settings.lineSpacing, fm.descent - fm.ascent
        )
        val paraS = settings.paragraphSpacing * density
        val indentPx = TextLayout.firstLineIndent(
            textPaint.measureText("国"), settings.firstLineIndent
        )

        paginator = Paginator(
            textPaint, box.width, box.height, lineH, paraS, justify, indentPx
        )
        pages = paginator!!.paginate(chapterIndex, chapterText)

        val explicit = pendingOffset
        pendingOffset = null
        pageIndex = when {
            explicit != null && pages.isNotEmpty() -> Paginator.pageIndexForOffset(pages, explicit)
            anchor >= 0 && pages.isNotEmpty() -> Paginator.pageIndexForOffset(pages, anchor)
            else -> pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        }
        invalidate()
        if (notify) notifyLayout()
    }

    private fun notifyLayout() {
        onLayoutChanged?.invoke(pageIndex, pages.size, currentCharOffset())
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
    val currentChapterIndex: Int get() = chapterIndex

    /** 当前页首字符在章节内的偏移 (用于进度保存/书签) */
    fun currentCharOffset(): Int = pages.getOrNull(pageIndex)?.startOffset ?: 0

    /** 当前页首行文本 (用于书签摘要) */
    fun currentLineText(): String =
        pages.getOrNull(pageIndex)?.lines?.firstOrNull()?.text ?: ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 屏幕尺寸 / 方向 / 分屏变化：重新分页并按字符偏移还原阅读位置
        rebuild(preservePosition = true, notify = true)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val page = pages.getOrNull(pageIndex) ?: return
        val xOffset = contentBox.left.toFloat()
        val yOffset = contentBox.top.toFloat()
        for (line in page.lines) {
            val text = line.text
            if (text.isEmpty()) continue
            // 逐字符按 Paginator 预算好的 charXs 绘制。两端对齐的额外字距已经算在
            // 里面，命中检测读的是同一份坐标，因此"点到的字"必然就是"看到的字"。
            val y = line.baseline + yOffset
            val xBase = xOffset + line.indent
            val xs = line.charXs
            for (i in text.indices) {
                canvas.drawText(text[i].toString(), xBase + xs[i], y, textPaint)
            }
        }
    }

    private fun cancelPendingLongPress() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }

    private fun hitWord(x: Float, y: Float): WordHit? {
        val page = pages.getOrNull(pageIndex) ?: return null
        return paginator?.wordAt(page, x - contentBox.left, y - contentBox.top)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                cancelPendingLongPress()
                longPress = Runnable {
                    longPressFired = true
                    val hit = hitWord(downX, downY)
                    if (hit != null && hit.word.isNotBlank())
                        onWordTap?.invoke(hit.word, hit.clickIndexInRun)
                }
                handler.postDelayed(longPress!!, 350)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > 12 || kotlin.math.abs(event.y - downY) > 12) {
                    cancelPendingLongPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
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
            MotionEvent.ACTION_CANCEL -> {
                // 父 View 拦截 / 窗口失焦时也要撤销长按，否则会对已 detach 的 View 弹词典
                cancelPendingLongPress()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        longPress = null
        super.onDetachedFromWindow()
    }
}
