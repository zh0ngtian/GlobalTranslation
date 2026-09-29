package com.example.globaltranslation.ui.camera

import android.content.Context
import android.graphics.*
import android.graphics.text.LineBreaker
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import com.example.globaltranslation.core.util.TextFrame
import com.example.globaltranslation.core.util.textFrame
import kotlin.math.max

data class OverlayPlacement(val blockId: String, val bounds: TextBounds, val fontSizePx: Float, val abbreviated: Boolean,
    val renderedCharacters: Int = 0, val rotationDegrees: Float = 0f)

/** Draws the captured bitmap and text through one transform, including rotation handled at capture. */
class PhotoOverlayView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var blocks: List<PhotoTextBlock> = emptyList()
    private var translations: Map<String, String> = emptyMap()
    private var click: (PhotoTextBlock) -> Unit = {}
    private val background = Paint().apply { color = Color.rgb(255, 253, 246) }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val missingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(186, 26, 26); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density
    }
    private var originalVisible = false
    fun showOriginal(value: Boolean) {
        if (originalVisible == value) return
        originalVisible = value
        hitRects = emptyList()
        placements = emptyList()
        accessibility.invalidateRoot()
        invalidate()
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
    private data class RenderedBlock(
        val source: RectF, val rect: RectF, val layout: StaticLayout?,
        val fontSize: Float, val contentScale: Float, val backgroundColor: Int, val frame: TextFrame
    )
    private var rendered = emptyList<RenderedBlock?>()
    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = hitIndex(x, y).takeIf { it >= 0 } ?: INVALID_ID
        override fun getVisibleVirtualViews(ids: MutableList<Int>) { ids.addAll(hitRects.indices.filter { !hitRects[it].isEmpty }) }
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
        rendered = prepareText(photo)
        hitRects = emptyList()
        invalidate()
        accessibility.invalidateRoot()
    }

    /** Measure once in photo pixels. Pinching scales the same glyphs, without reflowing the text. */
    private fun prepareText(photo: Bitmap): List<RenderedBlock?> {
        val sources = blocks.map { block ->
            TextBounds(block.bounds.left - 2, block.bounds.top - 2,
                block.bounds.right + 2, block.bounds.bottom + 2).clipped(photo.width, photo.height)?.toRectF()
        }
        val areas = sources.map { it?.let(::RectF) }
        // OCR can return slightly overlapping rows. Share the overlap before laying out glyphs.
        for (i in sources.indices) for (j in i + 1 until sources.size) {
            val a = sources[i] ?: continue
            val b = sources[j] ?: continue
            if (!RectF.intersects(a, b)) continue
            val vertical = kotlin.math.abs(kotlin.math.sin(Math.toRadians(blocks[i].rotationDegrees.toDouble()))) > .7 &&
                kotlin.math.abs(kotlin.math.sin(Math.toRadians(blocks[j].rotationDegrees.toDouble()))) > .7
            if (vertical) {
                val left = if (a.centerX() <= b.centerX()) i else j
                val right = if (left == i) j else i
                val boundary = (maxOf(a.left, b.left) + minOf(a.right, b.right)) / 2
                areas[left]?.let { it.right = minOf(it.right, boundary) }
                areas[right]?.let { it.left = maxOf(it.left, boundary) }
                continue
            }
            val upper = if (a.centerY() <= b.centerY()) i else j
            val lower = if (upper == i) j else i
            val boundary = (maxOf(a.top, b.top) + minOf(a.bottom, b.bottom)) / 2
            areas[upper]?.let { it.bottom = minOf(it.bottom, boundary) }
            areas[lower]?.let { it.top = maxOf(it.top, boundary) }
        }
        return blocks.mapIndexed { index, block ->
            val source = sources[index] ?: return@mapIndexed null
            val rect = areas[index]?.takeIf { it.width() > 0 && it.height() > 0 } ?: source
            val translated = translations[block.id]
            val color = sampleBackground(photo, source)
            val frame = textFrame(block, TextBounds(rect.left, rect.top, rect.right, rect.bottom))
            if (translated == null) return@mapIndexed RenderedBlock(source, rect, null, 0f, 1f, color, frame)
            val padding = minOf(photo.width / 960f, frame.width / 8, frame.height / 8)
            val availableWidthPx = (frame.width - 2 * padding).coerceAtLeast(0f)
            val availableWidth = availableWidthPx.toInt().coerceAtLeast(1)
            val availableHeight = (frame.height - 2 * padding).coerceAtLeast(0f)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114 > 90)
                    Color.rgb(22, 22, 25) else Color.WHITE
            }
            fun layout(size: Float): StaticLayout {
                paint.textSize = size
                return StaticLayout.Builder.obtain(translated, 0, translated.length, paint, availableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build()
            }
            val sourceLineHeight = frame.height / block.text.lines().size.coerceAtLeast(1)
            // Start from the source line height. The previous photo.width / 40 cap made
            // large lettering look like fine print even when the translation had ample room.
            // The fit check below still reduces longer translations until every glyph fits.
            val preferred = sourceLineHeight.coerceAtLeast(1f)
            val minimum = (preferred * .35f).coerceAtLeast(1f)
            fun fits(candidate: StaticLayout) = candidate.height <= availableHeight &&
                (0 until candidate.lineCount).all { candidate.getLineWidth(it) <= availableWidthPx }
            var size = minimum
            var best = layout(size)
            var contentScale = 1f
            if (fits(best)) {
                var low = minimum
                var high = preferred
                repeat(10) {
                    val candidateSize = (low + high) / 2
                    if (fits(layout(candidateSize))) { low = candidateSize; size = candidateSize }
                    else high = candidateSize
                }
                best = layout(size)
            } else {
                // Preserve every line. Scale the complete measured layout instead of ellipsizing.
                val widest = (0 until best.lineCount).maxOf { best.getLineWidth(it) }.coerceAtLeast(1f)
                contentScale = minOf(1f, availableHeight / best.height.coerceAtLeast(1), availableWidthPx / widest)

            }
            paint.textSize = size
            RenderedBlock(source, rect, best, size, contentScale, color, frame)
        }
    }

    /** An opaque local color hides source text without conspicuous white cards on a paper photo. */
    private fun sampleBackground(photo: Bitmap, rect: RectF): Int {
        val samples = (0..7).flatMap { step ->
            val x = rect.left + rect.width() * step / 7
            val y = rect.top + rect.height() * step / 7
            listOf(x to rect.top, x to rect.bottom, rect.left to y, rect.right to y)
        }.map { (x, y) -> photo.getPixel(x.toInt().coerceIn(0, photo.width - 1), y.toInt().coerceIn(0, photo.height - 1)) }
        fun median(channel: (Int) -> Int) = samples.map(channel).sorted()[samples.size / 2]
        return Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        try { drawPhoto(canvas) } finally { canvas.restoreToCount(saved) }
    }

    private fun drawPhoto(canvas: Canvas) {
        canvas.drawColor(Color.rgb(29, 30, 34))
        val photo = bitmap ?: return
        if (width <= 0 || height <= 0) return
        constrainPan()
        val base = fitPhoto(photo.width, photo.height, width, height)
        val transform = PhotoTransform(base.scale * zoom,
            width / 2f + (base.offsetX - width / 2f) * zoom + panX,
            height / 2f + (base.offsetY - height / 2f) * zoom + panY)
        val imageRect = transform.map(TextBounds(0f, 0f, photo.width.toFloat(), photo.height.toFloat())).toRectF()
        canvas.drawBitmap(photo, null, imageRect, imagePaint)
        if (originalVisible) { placements = emptyList(); hitRects = emptyList(); return }
        canvas.save()
        canvas.clipRect(imageRect)
        canvas.translate(transform.offsetX, transform.offsetY)
        canvas.scale(transform.scale, transform.scale)
        // Mask every source first, so a later OCR box cannot erase an earlier translation.
        rendered.filterNotNull().filter { it.layout != null }.forEach {
            background.color = it.backgroundColor
            canvas.drawRect(it.source, background)
        }
        val laidOut = mutableListOf<OverlayPlacement>()
        val hitAreas = mutableListOf<RectF>()
        val minTouch = 48 * resources.displayMetrics.density
        rendered.forEachIndexed { index, item ->
            if (item == null) { hitAreas += RectF(); return@forEachIndexed }
            val rect = item.rect
            val mapped = transform.map(TextBounds(rect.left, rect.top, rect.right, rect.bottom))
            val bounds = mapped.clipped(width, height)
            if (bounds == null) { hitAreas += RectF(); return@forEachIndexed }
            val screen = mapped.toRectF()
            val hit = RectF(screen.centerX() - max(screen.width(), minTouch) / 2,
                screen.centerY() - max(screen.height(), minTouch) / 2,
                screen.centerX() + max(screen.width(), minTouch) / 2,
                screen.centerY() + max(screen.height(), minTouch) / 2)
            hit.intersect(imageRect)
            hit.intersect(0f, 0f, width.toFloat(), height.toFloat())
            hitAreas += hit
            if (item.layout == null) {
                missingPaint.strokeWidth = 2 * resources.displayMetrics.density / transform.scale
                canvas.drawRect(rect, missingPaint)
                return@forEachIndexed
            }
            val frame = item.frame
            val padding = minOf(photo.width / 960f, frame.width / 8, frame.height / 8)
            canvas.save()
            canvas.clipRect(rect)
            canvas.translate(rect.centerX(), rect.centerY())
            canvas.rotate(frame.rotationDegrees)
            canvas.translate(-frame.width / 2 + padding, -frame.height / 2 + padding)
            canvas.scale(item.contentScale, item.contentScale)
            item.layout.draw(canvas)
            canvas.restore()
            laidOut += OverlayPlacement(blocks[index].id, bounds, item.fontSize * item.contentScale * transform.scale, false,
                item.layout.getLineEnd(item.layout.lineCount - 1), frame.rotationDegrees)
        }
        canvas.restore()
        val changed = hitRects != hitAreas
        hitRects = hitAreas
        placements = laidOut
        if (changed) accessibility.invalidateRoot()
    }

    private fun hitIndex(x: Float, y: Float): Int {
        placements.firstOrNull { x >= it.bounds.left && x <= it.bounds.right && y >= it.bounds.top && y <= it.bounds.bottom }
            ?.let { placement -> return blocks.indexOfFirst { it.id == placement.blockId } }
        return hitRects.indices.filter { hitRects[it].contains(x, y) }
        .minByOrNull { index ->
            val rect = hitRects[index]
            (rect.centerX() - x) * (rect.centerX() - x) + (rect.centerY() - y) * (rect.centerY() - y)
        } ?: -1
    }

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
                if (!moved && !originalVisible) blocks.getOrNull(hitIndex(event.x, event.y))?.let { performClick(); click(it) }
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
