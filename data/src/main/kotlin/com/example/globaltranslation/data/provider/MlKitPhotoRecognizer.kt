package com.example.globaltranslation.data.provider

import android.graphics.Bitmap
import com.example.globaltranslation.core.util.DocumentLayout
import com.example.globaltranslation.core.util.OcrWord
import com.example.globaltranslation.core.util.ParagraphLine
import com.example.globaltranslation.core.util.ParagraphText
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.*
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

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

    override suspend fun recognize(image: Any, script: TextScript): List<PhotoTextBlock> = withContext(Dispatchers.Default) {
        val input = image as? PhotoRecognitionInput
        val preview = (input?.preview ?: image) as Bitmap
        val client = recognizer(script)
        val initial = client.process(InputImage.fromBitmap(preview, 0)).await()
        val original = originalBlocks(initial, preview)
        val page = TextBounds(0f, 0f, preview.width.toFloat(), preview.height.toFloat())
        val words = words(initial, page, preview.width, preview.height, 0)
        if (words.isEmpty()) return@withContext original
        // Keep native vertical / side-turned writing intact. Manual photo rotation remains available.
        if (words.count { abs(it.angle) <= 15 } < words.size * .8) return@withContext original
        val h = DocumentLayout.medianHeight(words)
        val regions = DocumentLayout.columns(words, preview.width, preview.height)
        // Measure density at the same analysis size as RasterColumns. A high-resolution
        // photo can have readable pixels per letter and still be a dense multi-column page.
        val analysisScale = min(1f, 1800f / max(preview.width, preview.height))
        val small = h * analysisScale < 14 && initial.textBlocks.sumOf { it.lines.size } >= 12
        val needsOriginal = input?.readRegion != null && input.originalScale > 1.05f && h < 24 && words.size >= 20
        if (!small && !needsOriginal && regions.size == 1) return@withContext original
        val raster = if (small) RasterColumns.detect(preview, words) else null
        var tileId = 0
        suspend fun scan(areas: List<TextBounds>, probe: Boolean): List<OcrWord> {
            val found = mutableListOf<OcrWord>()
            val nativeScale = input?.originalScale ?: 1f
            for (area in areas) {
                val tiles = DocumentLayout.tiles(area,
                    if (probe) min(650f, 1800f / nativeScale) else area.width,
                    min(850f, 1800f / nativeScale), max(40f, h * 4))
                for (tile in tiles) {
                    coroutineContext.ensureActive()
                    val region = input?.readRegion?.invoke(tile) ?: run {
                        val left = floor(tile.left).toInt(); val top = floor(tile.top).toInt()
                        val right = ceil(tile.right).toInt().coerceAtMost(preview.width)
                        val bottom = ceil(tile.bottom).toInt().coerceAtMost(preview.height)
                        // createBitmap can return the input itself for a full-size crop; make owned pixels.
                        val crop = Bitmap.createBitmap(preview, left, top, right - left, bottom - top)
                        PhotoRecognitionRegion(if (crop === preview) crop.copy(Bitmap.Config.ARGB_8888, false) else crop,
                            TextBounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
                    }
                    val crop = region.pixels as Bitmap
                    val pixelScale = crop.height / region.bounds.height
                    val enlarge = min(4f, min(32f / (h * pixelScale).coerceAtLeast(1f), 2048f / max(crop.width, crop.height))).coerceAtLeast(1f)
                    val bitmap = if (enlarge > 1.1f) Bitmap.createScaledBitmap(crop, (crop.width * enlarge).roundToInt(), (crop.height * enlarge).roundToInt(), true) else crop
                    if (bitmap !== crop) crop.recycle()
                    val width = bitmap.width; val height = bitmap.height
                    val task = try { client.process(InputImage.fromBitmap(bitmap, 0)) }
                        catch (error: Exception) { bitmap.recycle(); throw error }
                    try { found += words(task.await(), region.bounds, width, height, ++tileId) }
                    finally {
                        // Cancellation of await does not cancel ML Kit's access to the image.
                        if (task.isComplete) bitmap.recycle() else task.addOnCompleteListener { bitmap.recycle() }
                    }
                }
            }
            return DocumentLayout.deduplicate(found)
        }
        if (raster != null) {
            val refined = scan(raster.crops, false).map { it.copy(readingRegion = raster.regionFor(it)) }
            return@withContext DocumentLayout.blocks(refined, raster.readingAreas).ifEmpty { original }
        }
        if (regions.size > 1) {
            val refined = scan(regions, false)
            return@withContext DocumentLayout.blocks(refined, regions).ifEmpty { original }
        }
        val probe = scan(listOf(page), true)
        val columns = DocumentLayout.columns(probe, preview.width, preview.height)
        val finalWords = if (columns.size > 1) scan(columns, false) else probe
        DocumentLayout.blocks(finalWords, columns).ifEmpty { original }
    }

    private fun words(result: Text, region: TextBounds, width: Int, height: Int, tile: Int): List<OcrWord> {
        val sx = region.width / width; val sy = region.height / height
        return result.textBlocks.flatMap { it.lines }.flatMap { line -> line.elements.mapNotNull { element ->
            val b = element.boundingBox ?: return@mapNotNull null
            if (element.text.isBlank()) return@mapNotNull null
            OcrWord(element.text, TextBounds(region.left + b.left * sx, region.top + b.top * sy,
                region.left + b.right * sx, region.top + b.bottom * sy), line.angle,
                element.cornerPoints?.map { PhotoPoint(region.left + it.x * sx, region.top + it.y * sy) }.orEmpty(), tile,
                minOf(b.left * sx, (width - b.right) * sx, b.top * sy, (height - b.bottom) * sy))
        } }
    }

    private fun originalBlocks(result: Text, image: Bitmap): List<PhotoTextBlock> =
        result.textBlocks.mapIndexedNotNull { index, block ->
            val text = block.text.trim()
            val box = block.boundingBox ?: return@mapIndexedNotNull null
            val bounds = TextBounds(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
                .clipped(image.width, image.height) ?: return@mapIndexedNotNull null
            val lines = block.lines.filter { it.angle.isFinite() }
            val x = lines.sumOf { cos(Math.toRadians(it.angle.toDouble())) * it.text.length.coerceAtLeast(1) }
            val y = lines.sumOf { sin(Math.toRadians(it.angle.toDouble())) * it.text.length.coerceAtLeast(1) }
            val angle = if (lines.isEmpty()) 0f else Math.toDegrees(atan2(y, x)).toFloat()
            val c = cos(Math.toRadians(angle.toDouble())).toFloat()
            val s = sin(Math.toRadians(angle.toDouble())).toFloat()
            val readingLines = block.lines.mapNotNull { line ->
                val points = line.cornerPoints ?: return@mapNotNull null
                if (points.isEmpty()) return@mapNotNull null
                val along = points.map { it.x * c + it.y * s }
                val across = points.map { -it.x * s + it.y * c }
                ParagraphLine(line.text, TextBounds(along.min(), across.min(), along.max(), across.max()))
            }
            // Missing line geometry must not silently join uncertain paragraph boundaries.
            val starts = if (readingLines.size == text.lines().size) ParagraphText.paragraphStarts(readingLines)
                else text.lines().indices.drop(1).toSet()
            if (text.isEmpty()) null else PhotoTextBlock("block_$index", text, bounds, angle,
                block.cornerPoints?.map { PhotoPoint(it.x.toFloat(), it.y.toFloat()) }.orEmpty(), starts)
        }
}
