package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.*
import kotlin.math.*

data class OcrWord(val text: String, val bounds: TextBounds, val angle: Float = 0f,
    val corners: List<PhotoPoint> = emptyList(), val tile: Int = 0, val edgeDistance: Float = 1000f, val readingRegion: Int? = null)

/** Geometry only: no language guessing, dictionary filtering, or invented source text. */
object DocumentLayout {
    fun medianHeight(words: List<OcrWord>) = words.map { it.bounds.height }.sorted().let { it.getOrElse(it.size / 2) { 20f } }

    fun columns(words: List<OcrWord>, width: Int, height: Int): List<TextBounds> {
        val full = TextBounds(0f, 0f, width.toFloat(), height.toFloat())
        if (words.size < 24) return listOf(full)
        var verticalCuts = 0
        fun split(region: TextBounds, values: List<OcrWord>, depth: Int): List<TextBounds> {
            if (depth >= 6 || values.size < 16) return listOf(region)
            val h = medianHeight(values).coerceAtLeast(3f)
            // First separate wide headings / footers, which would otherwise bridge column gutters.
            var bottom = values.minOf { it.bounds.top }
            var gap = 0f
            var cutY = 0f
            for (word in values.sortedBy { it.bounds.top }) {
                val r = word.bounds
                if (r.top - bottom > gap) { gap = r.top - bottom; cutY = (r.top + bottom) / 2 }
                bottom = max(bottom, r.bottom)
            }
            if (gap > max(h * 2.4f, region.height * .035f)) {
                val above = values.filter { (it.bounds.top + it.bounds.bottom) / 2 < cutY }
                val below = values - above.toSet()
                if (above.isNotEmpty() && below.isNotEmpty()) return split(region.copy(bottom = cutY), above, depth + 1) + split(region.copy(top = cutY), below, depth + 1)
            }
            val start = (region.left + region.width * .12f).toInt()
            val end = (region.right - region.width * .12f).toInt()
            val tolerance = (values.size * .004f).toInt()
            var runStart = -1
            val gaps = mutableListOf<Pair<Int, Int>>()
            for (x in start..end) {
                val occupancy = values.count { it.bounds.left < x && it.bounds.right > x }
                if (occupancy <= tolerance) { if (runStart < 0) runStart = x }
                else if (runStart >= 0) { gaps += runStart to x; runStart = -1 }
            }
            if (runStart >= 0) gaps += runStart to end
            for ((left, right) in gaps.sortedByDescending { it.second - it.first }) {
                if (right - left < max(h * .7f, region.width * .008f)) continue
                val x = (left + right) / 2f
                val a = values.filter { (it.bounds.left + it.bounds.right) / 2 < x }
                val b = values - a.toSet()
                if (a.size < 8 || b.size < 8) continue
                val overlap = min(a.maxOf { it.bounds.bottom }, b.maxOf { it.bounds.bottom }) - max(a.minOf { it.bounds.top }, b.minOf { it.bounds.top })
                if (overlap < h * 4) continue
                verticalCuts++
                return split(region.copy(right = x), a, depth + 1) + split(region.copy(left = x), b, depth + 1)
            }
            return listOf(region)
        }
        val regions = split(full, words, 0)
        return if (verticalCuts == 0) listOf(full) else regions
    }

    fun tiles(region: TextBounds, maxWidth: Float, maxHeight: Float, overlap: Float): List<TextBounds> {
        fun slices(start: Float, end: Float, limit: Float): List<Pair<Float, Float>> {
            val count = ceil((end - start) / limit).toInt().coerceAtLeast(1)
            val step = (end - start) / count
            return (0 until count).map { i -> max(start, start + i * step - if (i == 0) 0f else overlap / 2) to
                min(end, start + (i + 1) * step + if (i == count - 1) 0f else overlap / 2) }
        }
        return slices(region.left, region.right, maxWidth).flatMap { x ->
            slices(region.top, region.bottom, maxHeight).map { y -> TextBounds(x.first, y.first, x.second, y.second) }
        }
    }

    fun deduplicate(words: List<OcrWord>): List<OcrWord> {
        val kept = mutableListOf<OcrWord>()
        // Prefer complete interior words over fragments touching a crop boundary.
        for (word in words.sortedByDescending { min(it.edgeDistance, it.bounds.height * 2) + it.text.length * .01f }) {
            val a = word.bounds
            val duplicate = kept.any { other ->
                if (word.tile == other.tile) false else {
                    val b = other.bounds
                    val intersect = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
                    intersect > .65f * min(a.width * a.height, b.width * b.height) &&
                        abs((a.top + a.bottom) - (b.top + b.bottom)) < max(a.height, b.height)
                }
            }
            if (!duplicate) kept += word
        }
        return kept
    }

    fun blocks(words: List<OcrWord>, regions: List<TextBounds>): List<PhotoTextBlock> {
        val output = mutableListOf<PhotoTextBlock>()
        for ((regionIndex, region) in regions.withIndex()) {
            val selected = words.filter { if (it.readingRegion != null) return@filter it.readingRegion == regionIndex
                val b = it.bounds; (b.left + b.right) / 2 >= region.left &&
                (b.left + b.right) / 2 < region.right && (b.top + b.bottom) / 2 >= region.top && (b.top + b.bottom) / 2 < region.bottom }
            if (selected.isEmpty()) continue
            val angle = selected.map { it.angle }.sorted()[selected.size / 2]
            val radians = Math.toRadians(angle.toDouble())
            val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
            fun baseline(w: OcrWord) = (w.bounds.top + w.bounds.bottom) / 2 * c - (w.bounds.left + w.bounds.right) / 2 * s
            val rows = mutableListOf<MutableList<OcrWord>>()
            for (word in selected.sortedBy(::baseline)) {
                val row = rows.lastOrNull()
                if (row != null && abs(baseline(word) - row.map(::baseline).average()) < medianHeight(row) * .6f) row += word
                else rows += mutableListOf(word)
            }
            val paragraphs = mutableListOf<MutableList<List<OcrWord>>>()
            for (row in rows) {
                val previous = paragraphs.lastOrNull()?.lastOrNull()
                val gap = if (previous == null) Float.MAX_VALUE else row.minOf { it.bounds.top } - previous.maxOf { it.bounds.bottom }
                val height = medianHeight(row)
                val numbered = row.minBy { it.bounds.left }.text.matches(Regex("(?:[-•]|\\d+[.)])"))
                if (previous == null || gap > height * .9f || numbered || height > medianHeight(previous) * 1.5f ||
                    height < medianHeight(previous) * .65f || paragraphs.last().size >= 12) paragraphs += mutableListOf(row)
                else paragraphs.last() += row
            }
            for (paragraph in paragraphs) {
                val all = paragraph.flatten()
                val text = paragraph.joinToString("\n") { row -> row.sortedBy { it.bounds.left }.joinToString(" ") { it.text } }
                val points = all.flatMap { w -> w.corners.ifEmpty { val r = w.bounds; listOf(PhotoPoint(r.left, r.top), PhotoPoint(r.right, r.top), PhotoPoint(r.right, r.bottom), PhotoPoint(r.left, r.bottom)) } }
                val xs = points.map { it.x * c + it.y * s }; val ys = points.map { -it.x * s + it.y * c }
                val corners = listOf(xs.min() to ys.min(), xs.max() to ys.min(), xs.max() to ys.max(), xs.min() to ys.max()).map { (x, y) -> PhotoPoint(x * c - y * s, x * s + y * c) }
                val bounds = TextBounds(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                output += PhotoTextBlock("block_${output.size}", text, bounds, angle, corners)
            }
        }
        return output
    }
}
