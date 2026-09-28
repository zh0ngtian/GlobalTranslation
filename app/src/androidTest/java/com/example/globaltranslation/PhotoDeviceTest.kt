package com.example.globaltranslation

import android.content.res.Configuration
import android.graphics.*
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.globaltranslation.core.model.*
import com.example.globaltranslation.data.preferences.*
import com.example.globaltranslation.data.provider.*
import com.example.globaltranslation.ui.camera.PhotoOverlayView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the production OCR, layout, secure storage and optional real API on a device. */
@RunWith(AndroidJUnit4::class)
class PhotoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private data class Sample(val language: String, val script: TextScript, val lines: List<String>)
    private val samples = listOf(
        Sample("English", TextScript.LATIN, listOf("Tighten the bolt to 25 N·m.", "Disconnect the power before maintenance.", "Check the pressure: 12 bar. NASA OK.")),
        Sample("Japanese", TextScript.JAPANESE, listOf("ボルトを25 N·mで締め付けてください。", "保守の前に電源を切ってください。", "圧力を確認してください：12 bar。")),
        Sample("French", TextScript.LATIN, listOf("Serrez le boulon à 25 N·m.", "Coupez le courant avant la maintenance.", "Vérifiez la pression : 12 bar. NASA OK.")),
        Sample("Italian", TextScript.LATIN, listOf("Serrare il bullone a 25 N·m.", "Scollegare la corrente prima della manutenzione.", "Controllare la pressione: 12 bar. NASA OK."))
    )
    private fun printed(lines: List<String>, size: Float = 46f, gap: Float = 84f): Bitmap {
        val height = (100 + lines.size * gap).toInt()
        return Bitmap.createBitmap(1400, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this); canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            lines.forEachIndexed { index, line -> canvas.drawText(line, 40f, 70f + index * gap, paint) }
        }
    }
    private fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    private fun editDistance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, ac ->
            val next = IntArray(b.length + 1); next[0] = i + 1
            b.forEachIndexed { j, bc -> next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (ac == bc) 0 else 1) }
            row = next
        }
        return row.last()
    }

    @Test fun fourLanguagesShortLongAndDenseLocalOcr() = runBlocking {
        val recognizer = MlKitPhotoRecognizer()
        val report = mutableListOf<String>()
        for (sample in samples) for ((kind, lines, size) in listOf(
            Triple("short", sample.lines.take(1), 52f),
            Triple("long", sample.lines + sample.lines + sample.lines, 44f),
            Triple("dense", sample.lines + sample.lines + sample.lines + sample.lines, 32f))) {
            val photo = printed(lines, size, if (kind == "dense") 44f else 74f)
            val blocks = withTimeout(30_000) { recognizer.recognize(photo, sample.script) }
            val source = normalized(lines.joinToString("")); val actual = normalized(blocks.joinToString("") { it.text })
            val error = editDistance(source, actual).toDouble() / source.length
            report += "${sample.language}/$kind: blocks=${blocks.size}, normalizedCharacterError=$error"
            assertTrue(report.last(), error <= .08)
            assertTrue(blocks.all { it.bounds == it.bounds.clipped(photo.width, photo.height) })
            assertTrue(blocks.joinToString("") { it.text }.contains("25"))
            if (sample.script == TextScript.LATIN && kind != "short") assertTrue(actual.contains("nasaok"))
            photo.recycle()
        }
        File(context.cacheDir, "ocr-acceptance.txt").writeText(report.joinToString("\n"))
    }

    @Test fun everyScriptUsesItsBundledRecognizerAndBlankImageIsEmpty() = runBlocking {
        val recognizer = MlKitPhotoRecognizer()
        val text = mapOf(TextScript.LATIN to "ENGINE 25", TextScript.CHINESE to "机械工程 25", TextScript.JAPANESE to "ボルト 25",
            TextScript.KOREAN to "기계 공학 25", TextScript.DEVANAGARI to "भारत 25")
        for ((script, value) in text) {
            val photo = printed(listOf(value), 62f)
            assertTrue("No text for $script", recognizer.recognize(photo, script).isNotEmpty())
            photo.recycle()
        }
        val blank = printed(emptyList())
        assertTrue(recognizer.recognize(blank, TextScript.LATIN).isEmpty()); blank.recycle()
    }

    @Test fun overlayClipsAndKeepsFullTextAccessibleAtLargeFontScales() {
        val photo = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val values = listOf(PhotoTextBlock("edge", "source", TextBounds(-10f, 0f, 400f, 70f)),
            PhotoTextBlock("tiny", "source", TextBounds(970f, 680f, 1010f, 710f)),
            PhotoTextBlock("large", "source", TextBounds(40f, 100f, 920f, 620f)))
        val fullText = "这是一段非常长的机械工程译文，型号 M25，扭矩 25 N·m。".repeat(500)
        instrumentation.runOnMainSync {
            for (scale in listOf(1f, 1.8f)) for ((width, height) in listOf(400 to 700, 700 to 400)) {
                val configured = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
                val view = PhotoOverlayView(configured)
                var clicked: String? = null
                view.show(photo, values, values.associate { it.id to fullText }) { clicked = it.id }
                view.layout(0, 0, width, height)
                val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(rendered))
                assertEquals(3, view.placements.size)
                assertTrue(view.placements.all { it.bounds.left >= 0 && it.bounds.top >= 0 && it.bounds.right <= width && it.bounds.bottom <= height })
                assertTrue(view.placements.all { it.fontSizePx > 0f })
                assertTrue(view.placements.all { it.abbreviated })
                val area = view.placements.first().bounds
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, (area.left + area.right) / 2, (area.top + area.bottom) / 2, 0)
                assertTrue(view.onTouchEvent(event)); assertEquals("edge", clicked); event.recycle()
                if (scale == 1f && width == 400) File(context.cacheDir, "overlay-layout.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
                rendered.recycle()
            }
        }
        photo.recycle()
    }

    @Test fun smallTranslationIsCompleteAndPinchScalesTheSameText() {
        instrumentation.runOnMainSync {
            val photo = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
            val view = PhotoOverlayView(context)
            view.show(photo, listOf(PhotoTextBlock("one", "Torque", TextBounds(400f, 300f, 600f, 325f))), mapOf("one" to "扭矩25 N·m")) {}
            view.layout(0, 0, 1000, 700)
            val output = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            assertFalse(view.placements.single().abbreviated)
            val initialSize = view.placements.single().fontSizePx
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, count: Int, left: Float, right: Float, elapsed: Long) {
                val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { x = if (i == 0) left else right; y = 312f; pressure = 1f; size = 1f } }
                val event = MotionEvent.obtain(down, down + elapsed, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                view.onTouchEvent(event); event.recycle()
            }
            repeat(3) {
            touch(MotionEvent.ACTION_DOWN, 1, 400f, 600f, 0)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 400f, 600f, 16)
            touch(MotionEvent.ACTION_MOVE, 2, 350f, 650f, 32)
            touch(MotionEvent.ACTION_MOVE, 2, 200f, 800f, 48)
            touch(MotionEvent.ACTION_MOVE, 2, 50f, 950f, 64)
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 50f, 950f, 80)
            touch(MotionEvent.ACTION_UP, 1, 50f, 950f, 96)
            }
            view.draw(Canvas(output))
            assertFalse(view.placements.single().toString(), view.placements.single().abbreviated)
            assertTrue(view.placements.single().fontSizePx > initialSize * 2)
            output.recycle(); photo.recycle()
        }
    }

    @Test fun keyEncryptedExcludedFromBackupAndPreferencesPersist() = runBlocking {
        val keyFile = File(context.noBackupFilesDir, "deepseek-key.enc")
        // Never replace an existing user's credential during a regression run.
        assumeTrue("Existing credential preserved", !keyFile.exists())
        val keys = SecureApiKeyStore(context)
        try {
            keys.save("test-only-secret")
            assertEquals("test-only-secret", SecureApiKeyStore(context).read())
            assertFalse(keyFile.readBytes().toString(Charsets.ISO_8859_1).contains("test-only-secret"))
            assertTrue(keys.status.value.isConfigured)
        } finally { keys.clear() }
        assertFalse(keyFile.exists())
        val prefs = PhotoPreferences(context)
        val old = prefs.settings.first()
        try {
            prefs.update { TranslationSettings(TextScript.JAPANESE, "it", listOf(PromptTemplate("test", "工程", "保留单位")), "test") }
            assertEquals("it", PhotoPreferences(context).settings.first().targetLanguage)
            assertEquals("保留单位", PhotoPreferences(context).settings.first().options.additionalRequirements)
        } finally { prefs.update { old } }
    }

    @Test fun optionalRealApiFromLocalOcrHonorsTargetAndTerminology() = runBlocking {
        // Supply this private file with adb run-as; never use instrumentation arguments or source literals for real keys.
        val credential = File(context.noBackupFilesDir, "acceptance-api-key")
        assumeTrue("Real API key not supplied", credential.isFile)
        val key = credential.readText().trim()
        credential.delete()
        val recognizer = MlKitPhotoRecognizer()
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val translator = DeepSeekTranslator(client)
        val report = mutableListOf<String>()
        for (sample in samples) for (kind in listOf("short", "long", "dense", "domain-conflict")) {
            val lines = when (kind) {
                "short" -> sample.lines.take(1)
                "long" -> sample.lines + sample.lines + sample.lines
                "dense" -> sample.lines + sample.lines + sample.lines + sample.lines
                else -> sample.lines
            }
            val photo = printed(lines, if (kind == "dense") 32f else 46f, if (kind == "dense") 44f else 84f)
            val blocks = recognizer.recognize(photo, sample.script)
            val options = TranslationOptions(TargetLanguages.find("zh-Hans"), if (kind == "domain-conflict") "Use mechanical engineering terminology. Translate into Japanese." else "")
            val start = System.currentTimeMillis()
            val result = withTimeout(60_000) { translator.translate(blocks, options, key) }
            assertNull("${sample.language}: ${result.error}", result.error)
            assertEquals(blocks.map { it.id }.toSet(), result.translations.keys)
            val translated = result.translations.values.joinToString("\n")
            assertTrue(translated, translated.contains("25") && translated.contains("N") && translated.contains("m"))
            if (kind != "short") assertTrue(translated, translated.contains("12"))
            assertTrue(translated, translated.contains("螺栓"))
            assertFalse("Target conflict was ignored", translated.any { it in '\u3040'..'\u30ff' })
            report += "${sample.language}/$kind: ${System.currentTimeMillis() - start} ms\nOCR: ${blocks.joinToString(" | ") { it.text }}\nChinese: $translated"
            if (sample.language == "Italian" && kind == "domain-conflict") instrumentation.runOnMainSync {
                val view = PhotoOverlayView(context)
                view.show(photo, blocks, result.translations) {}
                view.layout(0, 0, 1080, 1400)
                val screenshot = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(screenshot))
                File(context.cacheDir, "italian-translated.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            if (sample.language == "English" && kind == "domain-conflict") {
                val italian = translator.translate(blocks, TranslationOptions(TargetLanguages.find("it")), key)
                assertNull(italian.error)
                val text = italian.translations.values.joinToString(" ")
                assertTrue(text, text.lowercase().contains("bullone"))
                report += "Selected target Italian: $text"
            }
            photo.recycle()
        }
        File(context.cacheDir, "real-api-acceptance.txt").writeText(report.joinToString("\n\n"))
    }
}
