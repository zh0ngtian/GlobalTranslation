package com.example.globaltranslation.ui.camera

import android.content.Context
import android.graphics.*
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.example.globaltranslation.core.model.PhotoTextBlock
import com.example.globaltranslation.core.model.TextBounds
import com.example.globaltranslation.core.util.fitPhoto
import com.example.globaltranslation.core.util.PhotoTransform
import kotlin.math.max

data class OverlayPlacement(val blockId: String, val bounds: TextBounds, val fontSizePx: Float, val abbreviated: Boolean)

/** Draws the captured bitmap and text through one transform, including rotation handled at capture. */
class PhotoOverlayView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var blocks: List<PhotoTextBlock> = emptyList()
    private var translations: Map<String, String> = emptyMap()
    private var click: (PhotoTextBlock) -> Unit = {}
    private val background = Paint().apply { color = Color.rgb(255, 253, 246) }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 22, 25) }
    private val missingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(186, 26, 26); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density
    }
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { moved = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            val ratio = next / zoom
            panX = detector.focusX - width / 2f - (detector.focusX - width / 2f - panX) * ratio
            panY = detector.focusY - height / 2f - (detector.focusY - height / 2f - panY) * ratio
            zoom = next
            constrainPan(); invalidate()
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    private fun constrainPan() {
        val photo = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val base = fitPhoto(photo.width, photo.height, width, height)
        val maxX = max(0f, (photo.width * base.scale * zoom - width) / 2)
        val maxY = max(0f, (photo.height * base.scale * zoom - height) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
    private var hitRects = emptyList<RectF>()
    var placements: List<OverlayPlacement> = emptyList()
        private set
    val minimumFontPx get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = hitIndex(x, y).takeIf { it >= 0 } ?: INVALID_ID
        override fun getVisibleVirtualViews(ids: MutableList<Int>) { ids.addAll(hitRects.indices) }
        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val block = blocks.getOrNull(id)
            node.contentDescription = block?.let { translations[it.id] ?: "未完成翻译：${it.text}" } ?: "文字"
            node.className = "android.widget.Button"
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            val rect = Rect()
            hitRects.getOrNull(id)?.roundOut(rect)
            node.setBoundsInParent(rect)
        }
        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val block = blocks.getOrNull(id) ?: return false
            click(block)
            return true
        }
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    fun show(photo: Bitmap, values: List<PhotoTextBlock>, translated: Map<String, String>, onClick: (PhotoTextBlock) -> Unit) {
        click = onClick
        if (bitmap === photo && blocks == values && translations == translated) return
        if (bitmap !== photo) { zoom = 1f; panX = 0f; panY = 0f }
        bitmap = photo; blocks = values; translations = translated
        hitRects = emptyList()
        invalidate()
        accessibility.invalidateRoot()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(29, 30, 34))
        val photo = bitmap ?: return
        if (width <= 0 || height <= 0) return
        constrainPan()
        val base = fitPhoto(photo.width, photo.height, width, height)
        val transform = PhotoTransform(base.scale * zoom,
            width / 2f + (base.offsetX - width / 2f) * zoom + panX,
            height / 2f + (base.offsetY - height / 2f) * zoom + panY)
        val photoBounds = transform.map(TextBounds(0f, 0f, photo.width.toFloat(), photo.height.toFloat()))
        val imageRect = photoBounds.toRectF()
        canvas.drawBitmap(photo, null, imageRect, imagePaint)
        canvas.save()
        canvas.clipRect(imageRect)
        val laidOut = mutableListOf<OverlayPlacement>()
        val hitAreas = mutableListOf<RectF>()
        val minTouch = 48 * resources.displayMetrics.density
        blocks.forEach { block ->
            val clipped = TextBounds(block.bounds.left - 2, block.bounds.top - 2, block.bounds.right + 2, block.bounds.bottom + 2).clipped(photo.width, photo.height)
            if (clipped == null) {
                hitAreas += RectF()
                return@forEach
            }
            val mapped = transform.map(clipped)
            val bounds = mapped.clipped(width, height)
            if (bounds == null) { hitAreas += RectF(); return@forEach }
            val rect = mapped.toRectF()
            val hit = RectF(rect.centerX() - max(rect.width(), minTouch) / 2,
                rect.centerY() - max(rect.height(), minTouch) / 2,
                rect.centerX() + max(rect.width(), minTouch) / 2,
                rect.centerY() + max(rect.height(), minTouch) / 2)
            hit.intersect(imageRect)
            hit.intersect(0f, 0f, width.toFloat(), height.toFloat())
            hitAreas += hit
            val translated = translations[block.id]
            if (translated == null) {
                canvas.drawRect(rect, missingPaint)
                return@forEach
            }
            canvas.drawRect(rect, background)
            canvas.save()
            canvas.clipRect(rect)
            val padding = minOf(2 * resources.displayMetrics.density, rect.width() / 8, rect.height() / 8)
            val availableWidth = (rect.width() - 2 * padding).toInt().coerceAtLeast(1)
            val availableHeight = (rect.height() - 2 * padding).coerceAtLeast(0f)
            val minimum = minimumFontPx
            fun layout(size: Float, maxLines: Int = Int.MAX_VALUE, ellipsize: Boolean = false): StaticLayout {
                textPaint.textSize = size
                return StaticLayout.Builder.obtain(translated, 0, translated.length, textPaint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setMaxLines(maxLines)
                    .apply { if (ellipsize) setEllipsize(TextUtils.TruncateAt.END) }
                    .build()
            }
            var best = layout(minimum)
            var size = minimum
            var abbreviated = best.height > availableHeight
            if (!abbreviated) {
                var low = minimum
                var high = max(minimum, minOf(availableHeight,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics)))
                repeat(7) {
                    val candidateSize = (low + high) / 2
                    val candidate = layout(candidateSize)
                    if (candidate.height <= availableHeight) { low = candidateSize; size = candidateSize }
                    else high = candidateSize
                }
                best = layout(size)
            } else {
                // Tiny boxes use a visible marker; the full translation remains available via touch/accessibility.
                val lines = (0 until best.lineCount).count { best.getLineBottom(it) <= availableHeight }
                if (lines == 0) {
                    textPaint.textSize = minimum
                    textPaint.color = Color.rgb(103, 80, 164)
                    canvas.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) / 3, textPaint)
                    textPaint.color = Color.rgb(22, 22, 25)
                    laidOut += OverlayPlacement(block.id, bounds, minimum, true)
                    canvas.restore()
                    return@forEach
                }
                best = layout(minimum, lines, true)
            }
            textPaint.textSize = size
            canvas.translate(rect.left + padding, rect.top + padding)
            best.draw(canvas)
            canvas.restore()
            laidOut += OverlayPlacement(block.id, bounds, size, abbreviated)
        }
        canvas.restore()
        val changed = hitRects != hitAreas
        hitRects = hitAreas
        placements = laidOut
        if (changed) accessibility.invalidateRoot()
    }

    private fun hitIndex(x: Float, y: Float): Int = hitRects.indices.filter { hitRects[it].contains(x, y) }
        .minByOrNull { index ->
            val rect = hitRects[index]
            (rect.centerX() - x) * (rect.centerX() - x) + (rect.centerY() - y) * (rect.centerY() - y)
        } ?: -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    if (moved || kotlin.math.abs(dx) + kotlin.math.abs(dy) > touchSlop) {
                        moved = true
                        if (zoom > 1f) { panX += dx; panY += dy; constrainPan(); invalidate() }
                    }
                }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { moved = true; return true }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved) blocks.getOrNull(hitIndex(event.x, event.y))?.let { performClick(); click(it) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { moved = true; parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun dispatchHoverEvent(event: MotionEvent) = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    private fun TextBounds.toRectF() = RectF(left, top, right, bottom)
}
