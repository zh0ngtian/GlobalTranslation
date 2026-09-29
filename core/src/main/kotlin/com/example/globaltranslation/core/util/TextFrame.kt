package com.example.globaltranslation.core.util

import com.example.globaltranslation.core.model.PhotoTextBlock
import com.example.globaltranslation.core.model.TextBounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A rectangle in reading coordinates; positive rotation is clockwise in photo coordinates. */
data class TextFrame(val width: Float, val height: Float, val rotationDegrees: Float)

fun textFrame(block: PhotoTextBlock, area: TextBounds): TextFrame {
    val angle = block.rotationDegrees.takeIf { it.isFinite() } ?: 0f
    val radians = Math.toRadians(angle.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val points = block.cornerPoints.takeIf { it.size == 4 && it.all { p -> p.x.isFinite() && p.y.isFinite() } }
    val dimensions = points?.let {
        val along = it.map { p -> p.x * c + p.y * s }
        val across = it.map { p -> -p.x * s + p.y * c }
        (along.max() - along.min() + 4f) to (across.max() - across.min() + 4f)
    }
    val width = dimensions?.first ?: if (abs(s) > abs(c)) area.height else area.width
    val height = dimensions?.second ?: if (abs(s) > abs(c)) area.width else area.height
    val rotatedWidth = abs(c) * width + abs(s) * height
    val rotatedHeight = abs(s) * width + abs(c) * height
    val scale = minOf(1f, area.width / rotatedWidth.coerceAtLeast(1f), area.height / rotatedHeight.coerceAtLeast(1f))
    return TextFrame(width * scale, height * scale, angle)
}
