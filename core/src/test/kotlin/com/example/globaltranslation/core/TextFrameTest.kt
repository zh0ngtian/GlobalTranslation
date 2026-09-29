package com.example.globaltranslation.core

import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.util.textFrame
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TextFrameTest {
    @Test fun rotatedReadingAreaFitsItsScreenBoundsWithoutSwappingTextDirection() {
        for (angle in listOf(0f, 12f, 45f, 90f, -90f, 180f)) {
            val c = cos(Math.toRadians(angle.toDouble())).toFloat()
            val s = sin(Math.toRadians(angle.toDouble())).toFloat()
            val points = listOf(-150f to -20f, 150f to -20f, 150f to 20f, -150f to 20f)
                .map { (x, y) -> PhotoPoint(200 + x * c - y * s, 200 + x * s + y * c) }
            val bounds = TextBounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            val frame = textFrame(PhotoTextBlock("one", "sample", bounds, angle, points), bounds)
            assertEquals(angle, frame.rotationDegrees, 0f)
            assertTrue(frame.width > frame.height * 6)
            assertTrue(abs(c) * frame.width + abs(s) * frame.height <= bounds.width + .001f)
            assertTrue(abs(s) * frame.width + abs(c) * frame.height <= bounds.height + .001f)
        }
    }

    @Test fun narrowBoxAloneDoesNotImplyVerticalText() {
        val bounds = TextBounds(10f, 20f, 40f, 220f)
        val horizontal = textFrame(PhotoTextBlock("one", "sample", bounds), bounds)
        assertEquals(30f, horizontal.width, .001f)
        assertEquals(200f, horizontal.height, .001f)
        val rotated = textFrame(PhotoTextBlock("one", "sample", bounds, 90f), bounds)
        assertEquals(200f, rotated.width, .001f)
        assertEquals(30f, rotated.height, .001f)
    }
}
