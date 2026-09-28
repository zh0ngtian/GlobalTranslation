package com.example.globaltranslation.data.provider

import android.graphics.Bitmap
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.core.provider.PhotoTextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MlKitPhotoRecognizer @Inject constructor() : PhotoTextRecognizer {
    private val recognizers = mutableMapOf<TextScript, TextRecognizer>()

    @Synchronized
    private fun recognizer(script: TextScript): TextRecognizer = recognizers.getOrPut(script) {
        TextRecognition.getClient(when (script) {
            TextScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            TextScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            TextScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        })
    }

    override suspend fun recognize(image: Any, script: TextScript): List<PhotoTextBlock> {
        require(image is Bitmap)
        val result = recognizer(script).process(InputImage.fromBitmap(image, 0)).await()
        return result.textBlocks.mapIndexedNotNull { index, block ->
            val text = block.text.trim()
            val box = block.boundingBox ?: return@mapIndexedNotNull null
            val bounds = TextBounds(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                .clipped(image.width, image.height) ?: return@mapIndexedNotNull null
            // Preserve short labels, technical acronyms, prices and units.
            if (text.isEmpty()) null else PhotoTextBlock("block_$index", text, bounds)
        }
    }
}
