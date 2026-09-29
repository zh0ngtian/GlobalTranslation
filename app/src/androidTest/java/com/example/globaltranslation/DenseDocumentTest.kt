package com.example.globaltranslation

import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.data.provider.MlKitPhotoRecognizer
import com.example.globaltranslation.ui.camera.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DenseDocumentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realOcrKeepsTwoColumnsSeparateAndRetainsHeading() = runBlocking {
        val image = Bitmap.createBitmap(1800, 1700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f }
        canvas.drawText("CITY GARDEN REGULATIONS", 80f, 90f, paint)
        paint.textSize = 30f
        for (i in 1..28) {
            canvas.drawText("ANIMALS protect birds number $i", 80f, 190f + i * 45, paint)
            canvas.drawText("VEHICLES keep roads clear $i", 80f + paint.measureText("ANIMALS protect birds number 28") + 28f, 190f + i * 45, paint)
        }
        val blocks = MlKitPhotoRecognizer().recognize(image, TextScript.LATIN)
        assertTrue(blocks.any { "CITY GARDEN" in it.text })
        assertFalse(blocks.any { "ANIMALS" in it.text && "VEHICLES" in it.text })
        val text = blocks.joinToString("\n") { it.text }
        assertEquals(28, Regex("ANIMALS").findAll(text).count())
        assertEquals(28, Regex("VEHICLES").findAll(text).count())
        assertTrue(blocks.all { it.bounds.left >= -1 && it.bounds.top >= -1 && it.bounds.right <= 1801 && it.bounds.bottom <= 1701 })
        image.recycle()
    }

    @Test fun smallPrintFourColumnsRetainsEveryNumberedRow() = runBlocking {
        val image = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 35f }
        canvas.drawText("PUBLIC PARK REGULATIONS", 60f, 110f, paint)
        paint.textSize = 14f
        val labels = listOf("BIRDS", "CARS", "TREES", "DOGS")
        for ((column, label) in labels.withIndex()) for (row in 1..36) {
            canvas.drawText("$label park regulation number $row", 50f + column * 220, 270f + row * 24, paint)
        }
        for (scale in listOf(1, 3)) {
            val candidate = if (scale == 1) image else Bitmap.createScaledBitmap(image, image.width * scale, image.height * scale, true)
            val blocks = MlKitPhotoRecognizer().recognize(candidate, TextScript.LATIN)
            assertFalse("Mixed columns at scale $scale", blocks.any { block -> labels.count { it in block.text } > 1 })
            val text = blocks.joinToString("\n") { it.text }
            for (label in labels) assertEquals("$label at scale $scale", 36, Regex(label).findAll(text).count())
            assertTrue(blocks.any { "PUBLIC PARK" in it.text })
            if (candidate !== image) candidate.recycle()
        }
        image.recycle()
    }

    @Test fun highResolutionRegionsSurviveExifAndManualRotation() = runBlocking {
        val file = File(context.cacheDir, "region-decoder-test.jpg")
        try {
            val image = Bitmap.createBitmap(6000, 800, Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.BLUE)
                drawRect(0f, 0f, 3000f, 400f, Paint().apply { color = Color.RED })
                drawRect(3000f, 400f, 6000f, 800f, Paint().apply { color = Color.GREEN })
            }
            for (orientation in listOf(1, 2, 5, 6, 7, 8)) {
                file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ExifInterface(file.path).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                }
                val input = loadSelectedPhotoInput(context.contentResolver, Uri.fromFile(file))
                val preview = input.preview as Bitmap
                assertEquals(4096, maxOf(preview.width, preview.height))
                assertTrue(input.originalScale > 1.4f)
                val area = TextBounds(preview.width * .1f, preview.height * .1f, preview.width * .2f, preview.height * .2f)
                val region = input.readRegion!!(area)
                val crop = region.pixels as Bitmap
                assertTrue(crop.width > area.width * 1.4f)
                val expected = preview.getPixel((preview.width * .15f).toInt(), (preview.height * .15f).toInt())
                val actual = crop.getPixel(crop.width / 2, crop.height / 2)
                assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 10)
                assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 10)
                crop.recycle()
                val rotated = rotatePhotoCounterClockwise(preview)
                val rotatedInput = input.rotated(preview.width, rotated)
                val rotatedCrop = rotatedInput.readRegion!!(TextBounds(rotated.width * .1f, rotated.height * .1f, rotated.width * .2f, rotated.height * .2f)).pixels as Bitmap
                val expectedRotation = rotated.getPixel((rotated.width * .15f).toInt(), (rotated.height * .15f).toInt())
                assertTrue(kotlin.math.abs(Color.red(expectedRotation) - Color.red(rotatedCrop.getPixel(rotatedCrop.width / 2, rotatedCrop.height / 2))) <= 10)
                rotatedCrop.recycle(); rotated.recycle(); preview.recycle()
            }
            image.recycle()
        } finally { file.delete() }
    }
}
