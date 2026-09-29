package com.example.globaltranslation.ui.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Decode the selected URI off the UI thread, including its EXIF orientation. */
suspend fun loadSelectedPhoto(resolver: ContentResolver, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        // OCR and the sampled overlay background both need CPU-readable pixels.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 4096) {
            val scale = 4096.0 / longest
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
    }
}

/** Keep only a bounded preview; reopen the selected URI for individual full-resolution regions. */
suspend fun loadSelectedPhotoInput(resolver: ContentResolver, uri: Uri): com.example.globaltranslation.core.model.PhotoRecognitionInput =
    withContext(Dispatchers.IO) {
        val preview = loadSelectedPhoto(resolver, uri)
        val orientation = runCatching {
            resolver.openInputStream(uri)!!.use { android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val dimensions = runCatching {
            resolver.openInputStream(uri)!!.use { stream ->
                val reader = requireNotNull(android.graphics.BitmapRegionDecoder.newInstance(stream, false))
                try { reader.width to reader.height } finally { reader.recycle() }
            }
        }.getOrNull() ?: return@withContext com.example.globaltranslation.core.model.PhotoRecognitionInput(preview)
        val rawWidth = dimensions.first
        val rawHeight = dimensions.second
        val transform = exifTransform(orientation)
        val full = android.graphics.RectF(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat())
        transform.mapRect(full)
        transform.postTranslate(-full.left, -full.top)
        val inverse = android.graphics.Matrix().also { check(transform.invert(it)) }
        val scaleX = full.width() / preview.width
        val scaleY = full.height() / preview.height
        com.example.globaltranslation.core.model.PhotoRecognitionInput(preview, maxOf(scaleX, scaleY)) { bounds ->
            withContext(Dispatchers.IO) {
                val rect = android.graphics.RectF(bounds.left * scaleX, bounds.top * scaleY, bounds.right * scaleX, bounds.bottom * scaleY)
                inverse.mapRect(rect)
                val crop = android.graphics.Rect()
                rect.roundOut(crop)
                check(crop.intersect(0, 0, rawWidth, rawHeight))
                var sample = 1
                while (maxOf(crop.width(), crop.height()) / sample > 2048) sample *= 2
                val decoded = resolver.openInputStream(uri)!!.use { stream ->
                    val reader = requireNotNull(android.graphics.BitmapRegionDecoder.newInstance(stream, false))
                    try { requireNotNull(reader.decodeRegion(crop, android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                    })) } finally { reader.recycle() }
                }
                val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, exifTransform(orientation), true)
                if (oriented !== decoded) decoded.recycle()
                val mapped = android.graphics.RectF(crop)
                transform.mapRect(mapped)
                com.example.globaltranslation.core.model.PhotoRecognitionRegion(oriented,
                    com.example.globaltranslation.core.model.TextBounds(mapped.left / scaleX, mapped.top / scaleY, mapped.right / scaleX, mapped.bottom / scaleY))
            }
        }
    }

private fun exifTransform(orientation: Int) = android.graphics.Matrix().apply {
    when (orientation) {
        2 -> setScale(-1f, 1f)
        3 -> setRotate(180f)
        4 -> setScale(1f, -1f)
        5 -> setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        6 -> setRotate(90f)
        7 -> setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
        8 -> setRotate(270f)
    }
}

fun com.example.globaltranslation.core.model.PhotoRecognitionInput.rotated(oldWidth: Int, rotated: Bitmap): com.example.globaltranslation.core.model.PhotoRecognitionInput {
    val reader = readRegion ?: return copy(preview = rotated)
    return copy(preview = rotated, readRegion = { bounds ->
        val region = reader(com.example.globaltranslation.core.model.TextBounds(oldWidth - bounds.bottom, bounds.left, oldWidth - bounds.top, bounds.right))
        val pixels = region.pixels as Bitmap
        val next = rotatePhotoCounterClockwise(pixels)
        if (next !== pixels) pixels.recycle()
        val r = region.bounds
        com.example.globaltranslation.core.model.PhotoRecognitionRegion(next,
            com.example.globaltranslation.core.model.TextBounds(r.top, oldWidth - r.right, r.bottom, oldWidth - r.left))
    })
}
