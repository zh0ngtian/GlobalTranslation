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
