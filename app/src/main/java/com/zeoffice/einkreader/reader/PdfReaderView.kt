package com.zeoffice.einkreader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PDF 阅读视图 — 基于框架 PdfRenderer, 逐页渲染位图。
 *
 * 手势:
 *  - 单指点击: 左 1/3 上一页, 右 1/3 下一页, 中间呼出菜单 (仅未缩放时)
 *  - 双指捏合: 0.5x ~ 4x 缩放, 缩放超过阈值后按新分辨率重渲染页面 (更清晰)
 *  - 缩放后单指拖动: 平移查看局部
 *
 * v0.4.8 修复:
 *  - open() 捕获损坏/加密/被删 PDF 的异常并返回错误信息，不再让异常冲出 Composition
 *  - 保存并释放 ParcelFileDescriptor（PdfRenderer 不负责关闭调用方传入的 fd）
 *  - 支持从上次阅读页码恢复
 *  - 位图按 renderZoom 渲染后，绘制倍率改为 zoom/renderZoom，消除「双重放大」
 */
class PdfReaderView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPage: PdfRenderer.Page? = null
    private var bitmap: Bitmap? = null
    private var pageIndex = 0
    private var pageCount = 0

    var onPageTurn: ((delta: Int) -> Unit)? = null
    var onCenterTap: (() -> Unit)? = null

    // ---- 缩放/平移状态 ----
    private var zoom = 1f                 // 目标显示缩放（相对 1x 页面）
    private var panX = 0f
    private var panY = 0f
    private var renderZoom = 1f           // 位图渲染分辨率倍率
    /** 位图已按 renderZoom 放大，绘制时只能再放大 zoom/renderZoom，否则会双重放大 */
    private val displayScale: Float get() = zoom / renderZoom.coerceAtLeast(0.01f)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var multiTouch = false

    private val maxDisplayZoom = 4f
    private val maxRenderZoom = 2.5f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = zoom
                val new = (zoom * detector.scaleFactor).coerceIn(0.5f, maxDisplayZoom)
                if (new != old) {
                    val fx = detector.focusX
                    val fy = detector.focusY
                    panX = fx - (fx - panX) * (new / old)
                    panY = fy - (fy - panY) * (new / old)
                    zoom = new
                    clampPan()
                    invalidate()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                settleZoom()
            }
        }
    )

    /**
     * 打开 PDF。
     * @return null = 成功；非 null = 可展示给用户的错误原因（绝不抛异常到 Compose）
     */
    fun open(file: File, startPage: Int = 0): String? {
        release()
        return try {
            if (!file.exists() || !file.canRead()) return "PDF 文件不存在或已被删除"
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fileDescriptor = pfd
            val r = PdfRenderer(pfd)
            renderer = r
            pageCount = r.pageCount
            if (pageCount <= 0) {
                release()
                return "PDF 没有可显示的页面"
            }
            pageIndex = startPage.coerceIn(0, pageCount - 1)
            openPage(pageIndex)
            null
        } catch (e: Exception) {
            release()
            "无法打开 PDF：${e.message ?: e.javaClass.simpleName}（文件可能损坏、加密或格式不支持）"
        }
    }

    /** 释放 native 资源：离开阅读页时调用，避免 fd 泄漏与位图堆积 */
    fun release() {
        closePage()
        runCatching { renderer?.close() }
        renderer = null
        runCatching { fileDescriptor?.close() }
        fileDescriptor = null
        pageCount = 0
        pageIndex = 0
        zoom = 1f
        renderZoom = 1f
        invalidate()
    }

    private fun openPage(i: Int, keepZoom: Boolean = false) {
        closePage()
        val r = renderer ?: return
        pageIndex = i.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        // View 还没测量完，等 onSizeChanged 再渲染，避免生成 1px 位图
        if (width <= 0 || height <= 0) {
            invalidate()
            return
        }
        if (!keepZoom) {
            zoom = 1f
            renderZoom = 1f
        }
        renderZoom = max(renderZoom, min(zoom, maxRenderZoom))
        val page = try {
            r.openPage(pageIndex)
        } catch (e: Exception) {
            invalidate()
            return
        }
        val baseW = width.coerceAtLeast(1)
        var scale = baseW.toFloat() / page.width.coerceAtLeast(1) * renderZoom
        var bw = (baseW * renderZoom).toInt().coerceAtLeast(1)
        var bh = (page.height * scale).toInt().coerceAtLeast(1)
        // 防止超高分辨率 OOM (总像素上限约 40MP)
        if (bw.toLong() * bh > 40_000_000L) {
            val shrink = kotlin.math.sqrt(40_000_000.0 / (bw.toLong() * bh)).toFloat()
            renderZoom *= shrink
            scale *= shrink
            bw = (bw * shrink).toInt().coerceAtLeast(1)
            bh = (bh * shrink).toInt().coerceAtLeast(1)
        }
        val bmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            runCatching { page.close() }
            invalidate()
            return
        }
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap = bmp
        currentPage = page
        resetPan()
        invalidate()
    }

    fun setPage(i: Int) = openPage(i)

    val pageCountValue: Int get() = pageCount
    val currentPageIndex: Int get() = pageIndex

    private fun closePage() {
        runCatching { currentPage?.close() }
        currentPage = null
        bitmap?.recycle()
        bitmap = null
    }

    private fun settleZoom() {
        if (zoom < 1.05f) {
            zoom = 1f
            renderZoom = 1f
            openPage(pageIndex, keepZoom = true)
        } else if (zoom > renderZoom * 1.15f) {
            openPage(pageIndex, keepZoom = true)
        } else {
            clampPan()
            invalidate()
        }
    }

    private fun resetPan() {
        val bmp = bitmap ?: return
        val s = displayScale
        val contentW = bmp.width * s
        val contentH = bmp.height * s
        panX = (width - contentW) / 2f
        panY = (height - contentH) / 2f
        clampPan()
    }

    /** 平移范围钳制: 内容小于视图时居中, 大于视图时不露出边界 */
    private fun clampPan() {
        val bmp = bitmap ?: return
        val s = displayScale
        val contentW = bmp.width * s
        val contentH = bmp.height * s
        panX = if (contentW <= width) (width - contentW) / 2f
        else panX.coerceIn(width - contentW, 0f)
        panY = if (contentH <= height) (height - contentH) / 2f
        else panY.coerceIn(height - contentH, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (renderer != null) openPage(pageIndex, keepZoom = true)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val bmp = bitmap ?: return
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(displayScale, displayScale)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                multiTouch = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!multiTouch && zoom > 1.01f && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (dragging || abs(dx) > 8 || abs(dy) > 8) {
                        dragging = true
                        panX += dx
                        panY += dy
                        clampPan()
                        invalidate()
                    }
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(event.x - downX) > 12 || abs(event.y - downY) > 12
                if (!multiTouch && !dragging && !moved) {
                    if (zoom <= 1.01f) {
                        val third = width / 3f
                        when {
                            downX < third -> onPageTurn?.invoke(-1)
                            downX > width - third -> onPageTurn?.invoke(1)
                            else -> onCenterTap?.invoke()
                        }
                    } else {
                        onCenterTap?.invoke()
                    }
                }
                if (!multiTouch) settleZoom()
                multiTouch = false
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                multiTouch = false
                dragging = false
                settleZoom()
            }
        }
        return super.onTouchEvent(event)
    }
}
