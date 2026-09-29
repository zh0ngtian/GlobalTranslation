package com.example.globaltranslation

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.data.provider.MlKitPhotoRecognizer
import com.example.globaltranslation.ui.camera.PhotoOverlayView
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TextDirectionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localOcrPreservesAnglesForUprightSidewaysAndTiltedText() = runBlocking {
        val recognizer = MlKitPhotoRecognizer()
        val evidence = JSONArray()
        for (angle in listOf(0f, 90f, -90f, 180f, 12f)) {
            val photo = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
            Canvas(photo).apply {
                drawColor(Color.WHITE); translate(450f, 450f); rotate(angle)
                drawText("PLEASE KEEP THIS TICKET", -300f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = Color.BLACK })
            }
            val blocks = recognizer.recognize(photo, TextScript.LATIN)
            val ticket = blocks.firstOrNull { "TICKET" in it.text }
            evidence.put(JSONObject().put("expectedAngle", angle).put("blocks", JSONArray().apply {
                blocks.forEach { put(JSONObject().put("source", it.text).put("angle", it.rotationDegrees).put("corners", it.cornerPoints.toString())) }
            }))
            File(context.cacheDir, "text-direction-ocr.json").writeText(evidence.toString(2))
            assertNotNull("No TICKET recognized at $angle", ticket)
            val difference = abs(((ticket!!.rotationDegrees - angle + 540) % 360) - 180)
            assertTrue("Expected $angle, found ${ticket.rotationDegrees}", difference < 5)
            assertEquals(4, ticket.cornerPoints.size)
            photo.recycle()
        }
    }

    @Test fun glyphPixelsRotateTogetherAndLongTranslationsRemainComplete() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val photo = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val bounds = TextBounds(30f, 50f, 330f, 100f)
            for (text in listOf("请保管好您的车票。", "请保管好您的车票，以备查验。".repeat(80))) {
                fun draw(angle: Float, area: TextBounds): Bitmap {
                    val view = PhotoOverlayView(context)
                    view.show(photo, listOf(PhotoTextBlock("one", "PLEASE KEEP THIS TICKET", area, angle)), mapOf("one" to text)) {}
                    view.layout(0, 0, 400, 400)
                    return Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).also {
                        view.draw(Canvas(it))
                        val placement = view.placements.single()
                        assertEquals(angle, placement.rotationDegrees, 0f)
                        assertFalse(placement.abbreviated)
                        assertEquals(text.length, placement.renderedCharacters)
                    }
                }
                val baseline = draw(0f, bounds)
                for (angle in listOf(90f, -90f, 180f)) {
                    val transform = Matrix().apply { setRotate(angle, 200f, 200f) }
                    val area = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom).apply { transform.mapRect(this) }
                    val actual = draw(angle, TextBounds(area.left, area.top, area.right, area.bottom))
                    val expected = Bitmap.createBitmap(baseline, 0, 0, 400, 400, Matrix().apply { postRotate(angle) }, false)
                    var mismatches = 0
                    for (y in 0 until 400) for (x in 0 until 400) {
                        if (abs(Color.red(actual.getPixel(x, y)) - Color.red(expected.getPixel(x, y))) > 20) mismatches++
                    }
                    assertTrue("Glyph rotation mismatch at $angle: $mismatches pixels", mismatches < 300)
                    if (text.length < 100) File(context.cacheDir, "text-direction-$angle.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    actual.recycle(); expected.recycle()
                }
                baseline.recycle()
            }
            photo.recycle()
        }
    }
}
