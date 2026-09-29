package com.example.globaltranslation.data.provider

import android.graphics.Bitmap
import com.example.globaltranslation.core.model.TextBounds
import com.example.globaltranslation.core.util.DocumentLayout
import com.example.globaltranslation.core.util.OcrWord
import kotlin.math.*

internal data class RasterColumnPlan(val crops: List<TextBounds>, val readingAreas: List<TextBounds>,
    val ownership: List<Pair<TextBounds, Int>>) {
    fun regionFor(word: OcrWord): Int? {
        val x = (word.bounds.left + word.bounds.right) / 2
        val y = (word.bounds.top + word.bounds.bottom) / 2
        return ownership.firstOrNull { (b, _) -> x >= b.left && x < b.right && y >= b.top && y < b.bottom }?.second
    }
}

/** Persistent low-ink gutters, measured in horizontal bands so perspective need not be perfect. */
internal object RasterColumns {
    fun detect(photo: Bitmap, words: List<OcrWord>): RasterColumnPlan? {
        val scale = min(1f, 1800f / max(photo.width, photo.height))
        val image = if (scale < 1) Bitmap.createScaledBitmap(photo, (photo.width * scale).roundToInt(), (photo.height * scale).roundToInt(), true) else photo
        try {
            val w = image.width; val h = image.height
            val letter = DocumentLayout.medianHeight(words) * scale
            val bodyWords = words.filter { it.bounds.height * scale < letter * 1.8f }
            if (bodyWords.size < 16 || letter < 2) return null
            val top = ((bodyWords.minOf { it.bounds.top } * scale) - letter * 1.5f).toInt().coerceAtLeast(0)
            val bottom = h
            val left = bodyWords.minOf { it.bounds.left } * scale
            val right = bodyWords.maxOf { it.bounds.right } * scale
            if (bottom - top < letter * 20 || right - left < letter * 18) return null
            val pixels = IntArray(w * h); image.getPixels(pixels, 0, w, 0, 0, w, h)
            val sum = IntArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0
                for (x in 0 until w) {
                    val color = pixels[y * w + x]
                    val gray = (((color shr 16) and 255) * 77 + ((color shr 8) and 255) * 150 + (color and 255) * 29) shr 8
                    pixels[y * w + x] = gray; row += gray
                    sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row
                }
            }
            val radius = (letter * .55f).roundToInt().coerceIn(3, 10)
            val ink = BooleanArray(w * h)
            for (y in top until bottom) for (x in 0 until w) {
                val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
                val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
                val total = sum[y1 * (w + 1) + x1] - sum[y0 * (w + 1) + x1] - sum[y1 * (w + 1) + x0] + sum[y0 * (w + 1) + x0]
                ink[y * w + x] = total.toFloat() / ((x1 - x0) * (y1 - y0)) - pixels[y * w + x] > 10
            }
            val count = ceil((bottom - top) / max(140f, letter * 24)).toInt().coerceIn(3, 8)
            val bands = (0 until count).map { i -> top + (bottom - top) * i / count to top + (bottom - top) * (i + 1) / count }
            val smoothRadius = (letter * .325f).roundToInt().coerceIn(1, 5)
            val searchRadius = max(letter * 2.5f, (right - left) * .02f).roundToInt()
            val candidates = bands.map { (y0, y1) ->
                val histogram = FloatArray(w) { x -> (y0 until y1).count { ink[it * w + x] }.toFloat() / (y1 - y0) }
                val smooth = FloatArray(w) { x -> (max(0, x - smoothRadius)..min(w - 1, x + smoothRadius)).map { histogram[it] }.average().toFloat() }
                val found = mutableListOf<Int>()
                val margin = max(letter * 8, (right - left) * .1f)
                for (x in (left + margin).toInt() until (right - margin).toInt()) {
                    if (x - searchRadius < 0 || x + searchRadius >= w || smooth[x] > .07f) continue
                    if (smooth[x] > (x - searchRadius..x + searchRadius).minOf { smooth[it] }) continue
                    if (found.lastOrNull()?.let { x - it < searchRadius } == true) continue
                    val span = (letter * 4).roundToInt()
                    val before = (max(0, x - span) until max(1, x - smoothRadius)).map { smooth[it] }.average()
                    val after = (min(w - 1, x + smoothRadius) until min(w, x + span)).map { smooth[it] }.average()
                    if (min(before, after) < max(.055, smooth[x] * 2.4)) continue
                    found += x
                }
                found
            }
            val tolerance = max(letter * 3, (right - left) * .025f)
            val tracks = mutableListOf<MutableMap<Int, Int>>()
            for ((band, values) in candidates.withIndex()) for (x in values) {
                val track = tracks.filter { band !in it }.minByOrNull { abs(it.values.average() - x) }
                if (track != null && abs(track.values.average() - x) <= tolerance) track[band] = x
                else tracks += mutableMapOf(band to x)
            }
            val gutters = tracks.filter { it.size >= ceil(count * .7f).toInt() }.sortedBy { it.values.average() }
            if (gutters.isEmpty() || gutters.size > 5) return null
            val medians = gutters.map { it.values.sorted()[it.size / 2] / scale }
            val xs = listOf(0f) + medians + photo.width.toFloat()
            if (xs.zipWithNext().any { (a, b) -> b - a < max(letter / scale * 8, photo.width * .08f) }) return null
            fun position(track: Map<Int, Int>, band: Int): Float {
                track[band]?.let { return it / scale }
                val lower = track.keys.filter { it < band }.maxOrNull()
                val upper = track.keys.filter { it > band }.minOrNull()
                return when {
                    lower != null && upper != null -> (track.getValue(lower) + (track.getValue(upper) - track.getValue(lower)) * (band - lower).toFloat() / (upper - lower)) / scale
                    else -> track.getValue(lower ?: requireNotNull(upper)) / scale
                }
            }
            val crops = mutableListOf<TextBounds>()
            val areas = mutableListOf<TextBounds>()
            val ownership = mutableListOf<Pair<TextBounds, Int>>()
            val startY = top / scale
            if (top > 0) { crops += TextBounds(0f, 0f, photo.width.toFloat(), startY); areas += crops.last(); ownership += crops.last() to 0 }
            for (column in 0 until xs.lastIndex) {
                areas += TextBounds(xs[column], startY, xs[column + 1], photo.height.toFloat())
                for ((band, ys) in bands.withIndex()) {
                    val x0 = if (column == 0) 0f else position(gutters[column - 1], band)
                    val x1 = if (column == gutters.size) photo.width.toFloat() else position(gutters[column], band)
                    ownership += TextBounds(x0, ys.first / scale, x1, ys.second / scale) to areas.lastIndex
                    val overlap = letter * 2
                    crops += TextBounds(x0, max(top.toFloat(), ys.first - overlap) / scale, x1, min(bottom.toFloat(), ys.second + overlap) / scale)
                }
            }
            return RasterColumnPlan(crops, areas, ownership)
        } finally { if (image !== photo) image.recycle() }
    }
}
